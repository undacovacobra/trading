"""Section 54 acceptance tests, run end-to-end through the HTTP webhook."""

from datetime import date

import pytest

from app.market_data.simulated import occ_symbol
from tests.conftest import Harness


def test_1_enter_long_buys_call_and_reduces_cash(h):
    r = h.send("ENTER_LONG", entry_price=24500.25, stop_price=24472.25, target_price=24584.25)
    assert r.status_code == 200 and r.json()["status"] == "accepted"

    pos = h.positions()
    assert len(pos) == 1
    p = pos[0]
    assert p["option_type"] == "CALL"
    assert p["option_underlying"] == "QQQ"
    assert p["strike"] == 604  # QQQ 603.72 -> ATM 604
    assert p["expiration"] == "2026-09-24"  # SAME_DAY (0DTE)
    assert p["mode"] == "OBSERVE" and p["state"] == "OPEN"
    # Fill = ask + $0.01 slippage, never the midpoint.
    assert p["entry_fill"] == pytest.approx(p["entry_ask"] + 0.01)
    assert p["entry_fill"] > p["entry_mid"]
    # $1,000 fixed-dollar sizing never exceeds the budget.
    assert p["quantity"] == int(1000 // (p["entry_fill"] * 100))
    assert p["gross_cost"] <= 1000

    acct = h.account()
    assert acct["cash"] == pytest.approx(25_000 - p["gross_cost"])
    assert acct["open_positions"] == 1


def test_2_duplicate_event_creates_no_additional_contracts(h):
    h.send("ENTER_LONG", "dup-1")
    cash_after_first = h.account()["cash"]
    r = h.send("ENTER_LONG", "dup-1")
    assert r.status_code == 200 and r.json()["status"] == "duplicate"
    assert len(h.positions()) == 1
    assert h.account()["cash"] == cash_after_first
    assert h.signal("dup-1")["duplicate_count"] == 1


def test_3_exit_long_closes_same_position_at_bid(h, clock):
    h.send("ENTER_LONG", "e1")
    opened = h.positions()[0]
    clock.advance(minutes=5)
    h.market.set_underlying("QQQ", 605.50)
    h.send("EXIT_LONG", "x1", futures_price=24560.0, reason="TARGET")

    assert h.positions() == []
    trades = h.get("/api/trades")
    assert len(trades) == 1
    t = trades[0]
    assert t["trade_uid"] == opened["trade_uid"]
    assert t["exit_fill"] == pytest.approx(t["exit_bid"] - 0.01)  # bid-based exit
    assert t["realized_pnl"] == pytest.approx(t["gross_proceeds"] - t["gross_cost"])
    assert t["exit_reason"] == "TARGET"
    acct = h.account()
    assert acct["cash"] == pytest.approx(25_000 + t["realized_pnl"])
    assert acct["realized_pnl"] == pytest.approx(t["realized_pnl"])


def test_4_enter_short_buys_put(h):
    h.send("ENTER_SHORT", entry_price=24483.5, stop_price=24511.5, target_price=24399.5)
    p = h.positions()[0]
    assert p["option_type"] == "PUT" and p["futures_direction"] == "SHORT"


@pytest.mark.parametrize("reason", ["STOP", "TARGET"])
def test_5_6_exit_reason_recorded(h, reason):
    h.send("ENTER_SHORT")
    h.send("EXIT_SHORT", reason=reason)
    assert h.positions() == []
    assert h.get("/api/trades")[0]["exit_reason"] == reason


def test_7_reversal_closes_then_opens_opposite(h):
    h.send("ENTER_LONG")
    call = h.positions()[0]
    h.send("REVERSE_SHORT", "rev-1")
    pos = h.positions()
    assert len(pos) == 1 and pos[0]["option_type"] == "PUT"
    closed = h.get("/api/trades")
    assert closed[0]["trade_uid"] == call["trade_uid"] and closed[0]["exit_reason"] == "REVERSAL"
    # The close happened before the open.
    detail_put = h.get(f"/api/trades/{pos[0]['trade_uid']}")
    assert detail_put["entry_timestamp"] >= closed[0]["exit_timestamp"]


def test_8_restart_preserves_open_position_and_balance(db_url, clock, market):
    with Harness(db_url, clock, market) as h1:
        h1.send("ENTER_LONG", "r-entry")
        before_pos = h1.positions()
        before_cash = h1.account()["cash"]
    with Harness(db_url, clock, market) as h2:
        assert h2.positions() == before_pos
        assert h2.account()["cash"] == before_cash
        # Replaying the same webhook after restart is still a no-op.
        assert h2.send("ENTER_LONG", "r-entry").json()["status"] == "duplicate"
        h2.send("EXIT_LONG", "r-exit")
        assert h2.positions() == []
        assert len(h2.get("/api/trades")) == 1


def test_9_spread_too_wide_skips_trade(h):
    cid = occ_symbol("QQQ", date(2026, 9, 24), "CALL", 604)
    h.market.set_quote_override(cid, bid=2.00, ask=2.60)  # ~26% spread
    h.send("ENTER_LONG", "wide-1")
    assert h.positions() == []
    ev = h.signal("wide-1")
    assert ev["status"] == "IGNORED" and ev["result_code"] == "SPREAD_TOO_WIDE"
    assert ev["trades"][0]["state"] == "REJECTED"
    assert h.account()["cash"] == 25_000


def test_10_insufficient_paper_cash_rejected(h):
    r = h.post("/api/control/paper-reset", {"confirmation": "RESET", "starting_balance": 300})
    assert r.status_code == 200, r.text
    h.send("ENTER_LONG", "poor-1")
    assert h.positions() == []
    ev = h.signal("poor-1")
    # $1,000 budget with $300 cash -> rejected, cash never negative
    assert ev["result_code"] == "PAPER_CASH_INSUFFICIENT"
    assert h.account()["cash"] == 300


def test_close_all_and_exit_without_position(h):
    h.send("EXIT_LONG", "orphan")
    assert h.signal("orphan")["result_code"] == "EXIT_WITHOUT_POSITION"
    h.send("ENTER_LONG")
    h.send("CLOSE_ALL", "ca")
    assert h.positions() == []
    assert h.signal("ca")["result_code"] == "CLOSED"


def test_entry_while_position_exists_rejected(h):
    h.send("ENTER_LONG")
    h.send("ENTER_LONG", "second")
    assert len(h.positions()) == 1
    assert h.signal("second")["result_code"] == "ENTRY_WHILE_POSITION_EXISTS"
    h.send("ENTER_SHORT", "opp")
    assert h.signal("opp")["result_code"] == "CONFLICTING_POSITION"


def test_exit_matches_tradingview_trade_id(h):
    h.update_risk(max_open_positions=2)
    h.send("ENTER_LONG", trade_id="tv-A")
    h.send("EXIT_LONG", "wrong-id", trade_id="tv-B")
    assert h.signal("wrong-id")["result_code"] == "EXIT_WITHOUT_POSITION"
    h.send("EXIT_LONG", "right-id", trade_id="tv-A")
    assert h.positions() == []


def test_unrealized_pnl_marks_at_bid(h, clock):
    h.send("ENTER_LONG")
    clock.advance(minutes=1)
    h.market.set_underlying("QQQ", 606.0)
    h.client.portal.call(h.app.state.engine.mark_to_market)
    p = h.positions()[0]
    assert p["unrealized_pnl"] == pytest.approx((p["current_bid"] - p["entry_fill"]) * 100 * p["quantity"])
    acct = h.account()
    assert acct["equity"] == pytest.approx(acct["cash"] + p["current_bid"] * 100 * p["quantity"])
    assert h.get("/api/equity")["points"]


def test_dry_run_creates_no_position(h):
    assert h.post("/api/control/mode", {"mode": "DRY_RUN"}).status_code == 200
    h.send("ENTER_LONG", "dry-1")
    assert h.positions() == []
    ev = h.signal("dry-1")
    assert ev["result_code"] == "DRY_RUN" and "WOULD BUY" in ev["result_message"]
    assert ev["trades"][0]["intent"]["quantity"] > 0
    assert h.account()["cash"] == 25_000


def test_statistics(h, clock):
    for i, (price, reason) in enumerate([(606.0, "TARGET"), (601.0, "STOP"), (607.0, "TARGET")]):
        h.market.set_underlying("QQQ", 603.72)
        h.send("ENTER_LONG", f"s-in-{i}")
        clock.advance(minutes=3)
        h.market.set_underlying("QQQ", price)
        h.send("EXIT_LONG", f"s-out-{i}", reason=reason)
        clock.advance(minutes=1)
    st = h.get("/api/stats", group_by="exit_reason")
    o = st["overall"]
    assert o["total_trades"] == 3 and o["wins"] == 2 and o["losses"] == 1
    assert o["win_rate"] == pytest.approx(66.67)
    assert o["average_time_in_trade_seconds"] == 180
    assert set(st["groups"]) == {"STOP", "TARGET"}
    rec = h.post("/api/control/reconcile").json()
    assert rec["ok"], rec
