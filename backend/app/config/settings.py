"""Environment-level settings (secrets, infrastructure, live-trading gate).

Everything that is safe to edit from the dashboard (risk limits, option
selection, sizing) lives in the database instead -- see services/config_store.py.
Secrets are only ever read from the environment, never from source code.
"""

import os
from dataclasses import dataclass, field


def _bool(name: str, default: bool = False) -> bool:
    return os.environ.get(name, str(default)).strip().lower() in ("1", "true", "yes", "on")


def _int(name: str, default: int) -> int:
    return int(os.environ.get(name, default))


@dataclass(frozen=True)
class EnvSettings:
    webhook_secret: str
    database_url: str = "sqlite:///./data/options_bridge.db"
    auto_migrate: bool = True

    # LIVE is impossible unless this is true in the environment AND confirmed in the dashboard.
    live_trading: bool = False
    # After a restart the system comes back PAUSED unless this is explicitly enabled.
    live_resume_on_restart: bool = False

    market_data_provider: str = "simulated"  # simulated | robinhood
    simulated_market_walk: bool = True

    dashboard_user: str = "admin"
    dashboard_password: str | None = None
    dashboard_auth_disabled: bool = False

    require_https: bool = False
    trust_proxy_headers: bool = False
    tradingview_ip_allowlist: bool = False
    webhook_rate_limit_per_minute: int = 60
    webhook_max_body_bytes: int = 16_384

    robinhood_mcp_url: str | None = None
    robinhood_mcp_token: str | None = field(default=None, repr=False)
    robinhood_agentic_account: str | None = None

    run_background_tasks: bool = True
    log_level: str = "INFO"

    def __repr__(self) -> str:  # never print secrets
        return f"EnvSettings(database_url={self.database_url!r}, live_trading={self.live_trading})"


def load_env_settings() -> EnvSettings:
    try:
        from dotenv import load_dotenv

        load_dotenv()
    except ImportError:
        pass
    secret = os.environ.get("WEBHOOK_SECRET", "")
    if len(secret) < 16:
        raise RuntimeError("WEBHOOK_SECRET must be set to a random string of at least 16 characters")
    return EnvSettings(
        webhook_secret=secret,
        database_url=os.environ.get("DATABASE_URL", EnvSettings.database_url),
        auto_migrate=_bool("AUTO_MIGRATE", True),
        live_trading=_bool("LIVE_TRADING", False),
        live_resume_on_restart=_bool("LIVE_RESUME_ON_RESTART", False),
        market_data_provider=os.environ.get("MARKET_DATA_PROVIDER", "simulated").lower(),
        simulated_market_walk=_bool("SIMULATED_MARKET_WALK", True),
        dashboard_user=os.environ.get("DASHBOARD_USER", "admin"),
        dashboard_password=os.environ.get("DASHBOARD_PASSWORD") or None,
        dashboard_auth_disabled=_bool("DASHBOARD_AUTH_DISABLED", False),
        require_https=_bool("REQUIRE_HTTPS", False),
        trust_proxy_headers=_bool("TRUST_PROXY_HEADERS", False),
        tradingview_ip_allowlist=_bool("TRADINGVIEW_IP_ALLOWLIST", False),
        webhook_rate_limit_per_minute=_int("WEBHOOK_RATE_LIMIT_PER_MINUTE", 60),
        webhook_max_body_bytes=_int("WEBHOOK_MAX_BODY_BYTES", 16_384),
        robinhood_mcp_url=os.environ.get("ROBINHOOD_MCP_URL") or None,
        robinhood_mcp_token=os.environ.get("ROBINHOOD_MCP_TOKEN") or None,
        robinhood_agentic_account=os.environ.get("ROBINHOOD_AGENTIC_ACCOUNT") or None,
        log_level=os.environ.get("LOG_LEVEL", "INFO"),
    )
