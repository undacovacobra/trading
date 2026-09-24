import os
from datetime import datetime

import pytest
from fastapi.testclient import TestClient

os.environ["OPTIONS_BRIDGE_NO_AUTOAPP"] = "1"

from app.clock import NY, FixedClock  # noqa: E402
from app.config.settings import EnvSettings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.market_data.simulated import SimulatedMarketData  # noqa: E402

SECRET = "test-secret-0123456789abcdef"
AUTH = ("admin", "pw")
# Thursday 2026-09-24 09:40 New York
MARKET_OPEN = datetime(2026, 9, 24, 9, 40, tzinfo=NY)


class Harness:
    def __init__(self, db_url: str, clock: FixedClock, market: SimulatedMarketData, **env_overrides):
        self.db_url = db_url
        self.clock = clock
        self.market = market
        self.env = EnvSettings(
            webhook_secret=SECRET,
            database_url=db_url,
            dashboard_password="pw",
            run_background_tasks=False,
            **env_overrides,
        )
        self.app = create_app(self.env, clock=clock, market_data=market)
        self.client = TestClient(self.app, raise_server_exceptions=True)
        self._seq = 0

    def __enter__(self):
        self.client.__enter__()
        return self

    def __exit__(self, *exc):
        self.client.__exit__(*exc)

    def drain(self):
        self.client.portal.call(self.app.state.worker.wait_idle)

    def send(self, action: str, event_id: str | None = None, *, drain=True, **fields):
        self._seq += 1
        body = {
            "schema_version": 1,
            "secret": SECRET,
            "event_id": event_id or f"evt-{self._seq}-{action.lower()}",
            "strategy_id": "reversal_v6",
            "symbol": "NQ1!",
            "action": action,
            "signal_time": self.clock.now().isoformat(),
        }
        body.update(fields)
        r = self.client.post("/api/webhooks/tradingview", json=body)
        if drain:
            self.drain()
        return r

    def get(self, path, **params):
        r = self.client.get(path, params=params, auth=AUTH)
        assert r.status_code == 200, r.text
        return r.json()

    def post(self, path, json=None):
        return self.client.post(path, json=json or {}, auth=AUTH)

    def put(self, path, json):
        return self.client.put(path, json=json, auth=AUTH)

    def account(self):
        return self.get("/api/account")["paper"]

    def positions(self):
        return self.get("/api/positions")

    def signal(self, event_id):
        return next(e for e in self.get("/api/signals") if e["event_id"] == event_id)

    def update_strategy(self, **changes):
        cfg = self.get("/api/config")["strategies"]["reversal_v6"]
        cfg.update(changes)
        r = self.put("/api/config/strategies/reversal_v6", cfg)
        assert r.status_code == 200, r.text

    def update_risk(self, **changes):
        cfg = self.get("/api/config")["risk"]
        cfg.update(changes)
        r = self.put("/api/config/risk", cfg)
        assert r.status_code == 200, r.text


@pytest.fixture
def db_url(tmp_path):
    return f"sqlite:///{tmp_path / 'test.db'}"


@pytest.fixture
def clock():
    return FixedClock(MARKET_OPEN)


@pytest.fixture
def market(clock):
    return SimulatedMarketData(clock, walk=False)


@pytest.fixture
def h(db_url, clock, market):
    with Harness(db_url, clock, market) as harness:
        yield harness
