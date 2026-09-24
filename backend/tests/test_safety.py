"""Architecture-level safety guarantees (sections 2, 31-33, 52, 67)."""

import ast
import asyncio
from pathlib import Path

import pytest
from sqlalchemy import select

from app.brokers.robinhood_mcp import (
    LiveAuthorization,
    RobinhoodReadClient,
    RobinhoodTradingClient,
    ToolNotPermitted,
)
from app.executors.paper import PaperExecutor
from app.models import EventStatus, WebhookEvent
from tests.conftest import Harness

APP = Path(__file__).resolve().parent.parent / "app"


def _imports(path: Path) -> set[str]:
    tree = ast.parse(path.read_text())
    mods = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module:
            mods.add(node.module)
        elif isinstance(node, ast.Import):
            mods.update(a.name for a in node.names)
    return mods


def test_observe_path_cannot_import_order_capable_code():
    for f in ["executors/paper.py", "executors/dry_run.py", "services/option_selector.py", "market_data/simulated.py"]:
        mods = _imports(APP / f)
        assert "app.brokers.robinhood_mcp" not in mods, f
        assert "app.executors.robinhood" not in mods, f
    # The Robinhood market-data provider only ever holds the read-only client.
    src = (APP / "market_data/robinhood.py").read_text()
    assert "RobinhoodTradingClient" not in src


def test_paper_executor_has_no_broker_reference():
    ex = PaperExecutor(clock=None)
    assert set(vars(ex)) == {"clock"}


def test_read_client_refuses_order_tools():
    client = RobinhoodReadClient("https://example.invalid/mcp", None)
    for tool in ("place_option_order", "review_option_order", "cancel_option_order"):
        with pytest.raises(ToolNotPermitted):
            asyncio.run(client.call_tool(tool, {}))


def test_trading_client_requires_live_authorization():
    with pytest.raises(ToolNotPermitted):
        RobinhoodTradingClient("https://example.invalid/mcp", None, authorization=None)
    with pytest.raises(ToolNotPermitted):
        LiveAuthorization("forged", object())


def test_extra_read_tools_cannot_include_order_tools(monkeypatch):
    monkeypatch.setenv("ROBINHOOD_MCP_EXTRA_READ_TOOLS", "get_account,place_option_order")
    with pytest.raises(ToolNotPermitted):
        RobinhoodReadClient("https://example.invalid/mcp", None)


def test_observe_is_default_and_live_refused_without_env(h):
    assert h.get("/api/status")["mode"] == "OBSERVE"
    r = h.post("/api/control/mode", {"mode": "LIVE", "confirmation": "ENABLE LIVE TRADING"})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "LIVE_DISABLED_BY_ENV"
    assert h.get("/api/status")["mode"] == "OBSERVE"


def test_live_requires_typed_confirmation(db_url, clock, market):
    with Harness(db_url, clock, market, live_trading=True) as h:
        r = h.post("/api/control/mode", {"mode": "LIVE"})
        assert r.json()["detail"]["code"] == "CONFIRMATION_REQUIRED"
        # Confirmation given, but the Robinhood adapter is not mapped/connected -> still refused.
        r = h.post("/api/control/mode", {"mode": "LIVE", "confirmation": "ENABLE LIVE TRADING"})
        assert r.status_code == 409
        assert h.get("/api/status")["mode"] == "OBSERVE"


def test_pause_blocks_entries_but_not_exits(h):
    h.send("ENTER_LONG")
    assert h.post("/api/control/pause").status_code == 200
    h.send("EXIT_LONG", "exit-while-paused")
    assert h.signal("exit-while-paused")["result_code"] == "CLOSED"
    h.send("ENTER_SHORT", "entry-while-paused")
    assert h.signal("entry-while-paused")["result_code"] == "PAUSED"
    assert h.positions() == []
    h.post("/api/control/resume")
    h.send("ENTER_SHORT", "after-resume")
    assert len(h.positions()) == 1


def test_emergency_close_closes_bot_positions_and_pauses(h):
    h.update_risk(max_open_positions=2)
    h.update_strategy(opposite_entry_policy="REJECT")
    h.send("ENTER_LONG", "a", trade_id="1")
    assert h.post("/api/control/emergency-close", {"confirmation": "nope"}).status_code == 400
    r = h.post("/api/control/emergency-close", {"confirmation": "CLOSE"}).json()
    assert r["requested"] == 1 and r["closed"] == 1
    assert h.positions() == []
    assert h.get("/api/status")["paused"] is True
    assert h.get("/api/trades")[0]["exit_reason"] == "EMERGENCY"


def test_risk_limit_disables_entries_keeps_exits(h, clock):
    h.update_risk(max_daily_loss=10)
    h.send("ENTER_LONG")
    clock.advance(minutes=2)
    h.market.set_underlying("QQQ", 598.0)  # losing trade
    h.send("EXIT_LONG", reason="STOP")
    assert h.get("/api/trades")[0]["realized_pnl"] < -10
    h.send("ENTER_LONG", "after-loss")
    assert h.signal("after-loss")["result_code"] == "MAX_DAILY_LOSS"
    assert any(e["code"] == "RISK_LIMIT_REACHED" for e in h.get("/api/system-events"))


def test_hard_max_trade_cost_caps_quantity(h):
    h.update_strategy(sizing_mode="FIXED_CONTRACTS", fixed_contracts=50, max_contracts=50)
    h.update_risk(max_trade_cost=500, max_contracts_per_trade=50)
    h.send("ENTER_LONG")
    p = h.positions()[0]
    assert p["gross_cost"] <= 500
    assert p["quantity"] == int(500 // (p["entry_fill"] * 100))


def test_outside_trading_hours(h, clock):
    clock.advance(hours=7)  # 16:40 ET
    h.send("ENTER_LONG", "late")
    assert h.signal("late")["result_code"] == "OUTSIDE_TRADING_HOURS"


def test_latest_0dte_entry(h, clock):
    clock.advance(hours=5, minutes=55)  # 15:35 ET, inside window but after 15:30
    h.send("ENTER_LONG", "late0dte")
    assert h.signal("late0dte")["result_code"] == "LATEST_0DTE_ENTRY_PASSED"


def test_interrupted_event_with_trade_state_is_not_replayed(db_url, clock, market):
    with Harness(db_url, clock, market) as h1:
        h1.send("ENTER_LONG", "crash-me")
        with h1.app.state.sessions() as s:
            ev = s.scalar(select(WebhookEvent).where(WebhookEvent.event_id == "crash-me"))
            ev.status = EventStatus.PROCESSING  # simulate a crash after trade rows were committed
            s.commit()
    with Harness(db_url, clock, market) as h2:
        assert h2.signal("crash-me")["status"] == "INTERRUPTED"
        st = h2.get("/api/status")
        assert st["out_of_sync"] is True
        assert len(h2.positions()) == 1  # no duplicate
        h2.send("ENTER_SHORT", "blocked")
        assert h2.signal("blocked")["result_code"] in ("SYSTEM_OUT_OF_SYNC", "CONFLICTING_POSITION")
        h2.send("EXIT_LONG", "exit-ok")
        assert h2.positions() == []  # exits still allowed


def test_received_but_unprocessed_event_runs_after_restart(db_url, clock, market):
    with Harness(db_url, clock, market) as h1:
        h1.app.state.worker.enqueue = lambda pk: None  # simulate crash before processing
        h1.send("ENTER_LONG", "pending-1", drain=False)
        assert h1.signal("pending-1")["status"] == "RECEIVED"
    with Harness(db_url, clock, market) as h2:
        h2.drain()
        assert h2.signal("pending-1")["status"] == "PROCESSED"
        assert len(h2.positions()) == 1


def test_test_signal_endpoint_refused_in_live_and_works_in_observe(h):
    r = h.post("/api/test-signal", {"action": "ENTER_LONG"})
    assert r.status_code == 200
    h.drain()
    assert len(h.positions()) == 1
