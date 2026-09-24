"""FastAPI application: TradingView futures signals -> options execution."""

import asyncio
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.api import dashboard, settings, tradingview
from app.api.security import RateLimiter
from app.clock import Clock
from app.config.settings import EnvSettings, load_env_settings
from app.db import Base, make_engine, make_sessionmaker
from app.executors.dry_run import DryRunExecutor
from app.executors.paper import PaperExecutor
from app.executors.registry import ExecutorRegistry
from app.market_data.base import MarketDataProvider
from app.services import config_store
from app.services.notifier import Notifier
from app.services.system_control import SystemControl
from app.services.trade_manager import TradeEngine
from app.services.worker import EventWorker

log = logging.getLogger("options_bridge")
BACKEND_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = Path(__file__).resolve().parent / "static"


def setup_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s.%(msecs)03d %(levelname)-8s %(name)s  %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )


def run_migrations(db_url: str) -> None:
    from alembic import command
    from alembic.config import Config

    cfg = Config(str(BACKEND_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND_DIR / "migrations"))
    cfg.set_main_option("sqlalchemy.url", db_url.replace("%", "%%"))
    command.upgrade(cfg, "head")


def build_market_data(env: EnvSettings, clock: Clock) -> MarketDataProvider:
    if env.market_data_provider == "robinhood":
        from app.brokers.robinhood_mcp import RobinhoodReadClient
        from app.market_data.robinhood import RobinhoodMarketData

        return RobinhoodMarketData(RobinhoodReadClient(env.robinhood_mcp_url, env.robinhood_mcp_token))
    if env.market_data_provider == "simulated":
        from app.market_data.simulated import SimulatedMarketData

        return SimulatedMarketData(clock, walk=env.simulated_market_walk)
    raise RuntimeError(f"Unknown MARKET_DATA_PROVIDER {env.market_data_provider!r}")


async def _periodic(name: str, interval_fn, fn) -> None:
    while True:
        try:
            await asyncio.sleep(interval_fn())
            await fn()
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            logging.getLogger("options_bridge.bg").exception("%s failed", name)


def create_app(
    env: EnvSettings | None = None,
    *,
    clock: Clock | None = None,
    market_data: MarketDataProvider | None = None,
) -> FastAPI:
    env = env or load_env_settings()
    clock = clock or Clock()
    setup_logging(env.log_level)

    db_engine = make_engine(env.database_url)
    sessions = make_sessionmaker(db_engine)
    market_data = market_data or build_market_data(env, clock)
    notifier = Notifier(clock)
    paper = PaperExecutor(clock)
    executors = ExecutorRegistry(paper, DryRunExecutor(clock, paper))
    engine = TradeEngine(sessions, market_data, executors, clock, notifier)
    worker = EventWorker(engine, sessions, notifier, clock)
    control = SystemControl(env, engine, clock, notifier)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if env.auto_migrate:
            run_migrations(env.database_url)
        else:
            Base.metadata.create_all(db_engine)
        with sessions() as s:
            config_store.seed_defaults(s)
        await control.startup()
        await worker.start()
        tasks = []
        if env.run_background_tasks:

            def quote_interval():
                with sessions() as s:
                    return config_store.get_system(s).quote_interval_seconds

            tasks.append(asyncio.create_task(_periodic("mark_to_market", quote_interval, engine.mark_to_market)))
            tasks.append(asyncio.create_task(_periodic("reconcile", lambda: 300, control.reconcile)))
        log.info("ready: market data=%s", market_data.name)
        yield
        for t in tasks:
            t.cancel()
        await worker.stop()
        await market_data.close()
        if executors.live is not None:
            await executors.live.client.close()
        db_engine.dispose()

    app = FastAPI(title="TradingView -> Options Bridge", lifespan=lifespan)
    app.state.env = env
    app.state.clock = clock
    app.state.sessions = sessions
    app.state.market_data = market_data
    app.state.notifier = notifier
    app.state.executors = executors
    app.state.engine = engine
    app.state.worker = worker
    app.state.control = control
    app.state.rate_limiter = RateLimiter(env.webhook_rate_limit_per_minute)

    app.include_router(tradingview.router)
    app.include_router(dashboard.router)
    app.include_router(settings.router)

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/")
    async def index():
        return FileResponse(STATIC_DIR / "index.html")

    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    return app


if os.environ.get("OPTIONS_BRIDGE_NO_AUTOAPP") != "1":  # uvicorn app.main:app
    try:
        app = create_app()
    except RuntimeError as e:  # e.g. WEBHOOK_SECRET missing when imported by tooling
        logging.getLogger("options_bridge").error("app not created: %s", e)
        app = None
