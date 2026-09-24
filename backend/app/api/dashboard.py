"""Read-only dashboard endpoints."""

from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import select

from app.api.security import require_dashboard_auth
from app.clock import NY
from app.models import EquitySnapshot, Trade, WebhookEvent
from app.models import SystemEvent as SystemEventRow
from app.services import config_store, paper_account, stats
from app.services.market_calendar import is_trading_day, ny_date, session_close
from app.services.position_sizer import CONTRACT_MULTIPLIER
from app.services.state_machine import HOLDING_STATES, TradeState

router = APIRouter(prefix="/api", dependencies=[Depends(require_dashboard_auth)])


def _iso(dt):
    return dt.isoformat() if dt else None


def _ms(a, b):
    if a is None or b is None:
        return None
    return round((b - a).total_seconds() * 1000)


def trade_dict(t: Trade, detail: bool = False) -> dict:
    mark = t.current_bid if t.current_bid is not None else t.entry_bid
    value = round(mark * CONTRACT_MULTIPLIER * t.quantity, 2) if mark is not None and t.quantity else None
    unrealized = round(value - t.gross_cost, 2) if value is not None and t.gross_cost is not None and t.state in HOLDING_STATES else None
    d = {
        "trade_uid": t.trade_uid,
        "strategy_id": t.strategy_id,
        "mode": t.mode,
        "state": t.state,
        "source_event_id": t.source_event_id,
        "exit_event_id": t.exit_event_id,
        "tv_trade_id": t.tv_trade_id,
        "futures_symbol": t.futures_symbol,
        "futures_root": t.futures_root,
        "futures_direction": t.futures_direction,
        "futures_entry": t.futures_entry,
        "futures_stop": t.futures_stop,
        "futures_target": t.futures_target,
        "futures_exit": t.futures_exit,
        "signal_type": t.signal_type,
        "option_underlying": t.option_underlying,
        "underlying_price_at_signal": t.underlying_price_at_signal,
        "option_symbol": t.option_symbol,
        "option_type": t.option_type,
        "strike": t.strike,
        "expiration": t.expiration.isoformat() if t.expiration else None,
        "expiration_mode": t.expiration_mode,
        "strike_mode": t.strike_mode,
        "quantity": t.quantity,
        "entry_bid": t.entry_bid,
        "entry_ask": t.entry_ask,
        "entry_mid": t.entry_mid,
        "entry_fill": t.entry_fill,
        "entry_timestamp": _iso(t.entry_timestamp),
        "exit_bid": t.exit_bid,
        "exit_ask": t.exit_ask,
        "exit_fill": t.exit_fill,
        "exit_timestamp": _iso(t.exit_timestamp),
        "exit_reason": t.exit_reason,
        "gross_cost": t.gross_cost,
        "gross_proceeds": t.gross_proceeds,
        "fees": t.fees,
        "realized_pnl": t.realized_pnl,
        "return_percent": t.return_percent,
        "current_bid": t.current_bid,
        "current_ask": t.current_ask,
        "current_mid": round((t.current_bid + t.current_ask) / 2, 4) if t.current_bid is not None and t.current_ask is not None else None,
        "current_quote_time": _iso(t.current_quote_time),
        "current_value": value,
        "unrealized_pnl": unrealized,
        "unrealized_pct": round(unrealized / t.gross_cost * 100, 2) if unrealized is not None and t.gross_cost else None,
        "reject_code": t.reject_code,
        "reject_message": t.reject_message,
        "created_at": _iso(t.created_at),
        "closed_at": _iso(t.closed_at),
        "timing_ms": {
            "webhook_latency": _ms(t.signal_time, t.webhook_received_at),
            "contract_selection": _ms(t.webhook_received_at, t.contract_selected_at),
            "execution": _ms(t.execution_requested_at, t.entry_timestamp),
            "signal_to_fill": _ms(t.signal_time or t.webhook_received_at, t.entry_timestamp),
            "exit_signal_to_fill": _ms(t.exit_signal_time or t.exit_received_at, t.exit_timestamp),
        },
    }
    if detail:
        d["intent"] = t.intent
        d["selection_notes"] = t.selection_notes
        d["transitions"] = [
            {"from": x.from_state, "to": x.to_state, "at": _iso(x.at), "note": x.note} for x in t.transitions
        ]
        d["orders"] = [
            {
                "client_order_id": o.client_order_id,
                "side": o.side,
                "order_type": o.order_type,
                "status": o.status,
                "requested_quantity": o.requested_quantity,
                "filled_quantity": o.filled_quantity,
                "limit_price": o.limit_price,
                "avg_fill_price": o.avg_fill_price,
                "bid_at_request": o.bid_at_request,
                "ask_at_request": o.ask_at_request,
                "mid_at_request": o.mid_at_request,
                "quote_time": _iso(o.quote_time),
                "slippage_assumption": o.slippage_assumption,
                "broker_order_id": o.broker_order_id,
                "error": o.error,
                "filled_at": _iso(o.filled_at),
            }
            for o in t.orders
        ]
    return d


@router.get("/status")
async def get_status(request: Request):
    st = request.app.state
    now = st.clock.now()
    with st.sessions() as s:
        system = config_store.get_system(s)
    live = st.executors.live
    if system.mode.value == "LIVE":
        account_label = live.account_label() if live else "LIVE (not connected)"
    else:
        account_label = st.executors.get(system.mode.value).account_label()
    today = ny_date(now)
    close = session_close(today)
    return {
        "mode": system.mode.value,
        "paused": system.paused,
        "out_of_sync": system.out_of_sync,
        "out_of_sync_reason": system.out_of_sync_reason,
        "live_trading_env": st.env.live_trading,
        "live_connected": live is not None,
        "account_label": account_label,
        "market_data": {"provider": st.market_data.name, "simulated": st.market_data.is_simulated},
        "server_time": now.isoformat(),
        "ny_time": now.astimezone(NY).isoformat(),
        "market": {"trading_day": is_trading_day(today), "session_close": close.isoformat() if close else None},
        "worker_queue": st.worker.pending(),
        "quote_interval_seconds": system.quote_interval_seconds,
    }


@router.get("/account")
async def get_account(request: Request):
    st = request.app.state
    with st.sessions() as s:
        system = config_store.get_system(s)
        acct = paper_account.get_active_account(s, starting_balance=system.paper_starting_balance, now=st.clock.now())
        out = {"paper": paper_account.summary(s, acct)}
        s.commit()
    out["live"] = None
    live = st.executors.live
    if live is not None:
        try:
            with st.sessions() as s:
                out["live"] = {
                    "account": live.account_label(),
                    "equity": await live.get_account_equity(s),
                    "buying_power": await live.get_buying_power(s),
                }
        except Exception as e:  # noqa: BLE001
            out["live"] = {"account": live.account_label(), "error": str(e)}
    return out


@router.get("/positions")
async def get_positions(request: Request):
    with request.app.state.sessions() as s:
        rows = s.scalars(select(Trade).where(Trade.state.in_(HOLDING_STATES)).order_by(Trade.id)).all()
        return [trade_dict(t) for t in rows]


@router.get("/trades")
async def get_trades(request: Request, limit: int = Query(200, le=2000), mode: str | None = None, strategy_id: str | None = None):
    with request.app.state.sessions() as s:
        q = select(Trade).where(Trade.state == TradeState.CLOSED)
        if mode:
            q = q.where(Trade.mode == mode)
        if strategy_id:
            q = q.where(Trade.strategy_id == strategy_id)
        rows = s.scalars(q.order_by(Trade.closed_at.desc(), Trade.id.desc()).limit(limit)).all()
        return [trade_dict(t) for t in rows]


@router.get("/trades/{trade_uid}")
async def get_trade(request: Request, trade_uid: str):
    with request.app.state.sessions() as s:
        t = s.scalar(select(Trade).where(Trade.trade_uid == trade_uid))
        if t is None:
            raise HTTPException(404, "Trade not found")
        return trade_dict(t, detail=True)


@router.get("/signals")
async def get_signals(request: Request, limit: int = Query(100, le=1000)):
    """Recent webhook events with what the engine decided (incl. skips and dry-run intents)."""
    with request.app.state.sessions() as s:
        events = s.scalars(select(WebhookEvent).order_by(WebhookEvent.id.desc()).limit(limit)).all()
        ids = [e.event_id for e in events]
        trades = s.scalars(select(Trade).where(Trade.source_event_id.in_(ids))).all() if ids else []
        by_event: dict[str, list[dict]] = {}
        for t in trades:
            by_event.setdefault(t.source_event_id, []).append(
                {"trade_uid": t.trade_uid, "state": t.state, "option_symbol": t.option_symbol, "quantity": t.quantity,
                 "strike": t.strike, "option_type": t.option_type, "expiration": t.expiration.isoformat() if t.expiration else None,
                 "entry_bid": t.entry_bid, "entry_ask": t.entry_ask, "intent": t.intent, "reject_code": t.reject_code}
            )
        return [
            {
                "event_id": e.event_id,
                "source": e.source,
                "strategy_id": e.strategy_id,
                "symbol": e.symbol,
                "action": e.action,
                "status": e.status,
                "result_code": e.result_code,
                "result_message": e.result_message,
                "signal_time": _iso(e.signal_time),
                "received_at": _iso(e.received_at),
                "processed_at": _iso(e.processed_at),
                "duplicate_count": e.duplicate_count,
                "payload": e.payload,
                "trades": by_event.get(e.event_id, []),
            }
            for e in events
        ]


@router.get("/stats")
async def get_stats(request: Request, mode: str = "OBSERVE", group_by: str | None = None, account: str = "active"):
    if group_by and group_by not in stats.GROUPERS:
        raise HTTPException(400, f"group_by must be one of {sorted(stats.GROUPERS)}")
    st = request.app.state
    with st.sessions() as s:
        q = select(Trade).where(Trade.state == TradeState.CLOSED, Trade.mode == mode)
        if mode == "OBSERVE" and account == "active":
            system = config_store.get_system(s)
            acct = paper_account.get_active_account(s, starting_balance=system.paper_starting_balance, now=st.clock.now())
            q = q.where(Trade.paper_account_id == acct.id)
        trades = s.scalars(q).all()
        out = {"overall": stats.compute(trades)}
        if group_by:
            out["group_by"] = group_by
            out["groups"] = stats.grouped(trades, group_by)
        return out


@router.get("/equity")
async def get_equity(request: Request, range: str = Query("all", pattern="^(today|week|month|all)$")):
    st = request.app.state
    now = st.clock.now()
    with st.sessions() as s:
        system = config_store.get_system(s)
        acct = paper_account.get_active_account(s, starting_balance=system.paper_starting_balance, now=now)
        q = select(EquitySnapshot).where(EquitySnapshot.account_id == acct.id)
        if range != "all":
            since = {"today": timedelta(days=1), "week": timedelta(days=7), "month": timedelta(days=31)}[range]
            q = q.where(EquitySnapshot.at >= now - since)
        snaps = s.scalars(q.order_by(EquitySnapshot.at)).all()
        # Thin to at most ~1000 points for the chart.
        step = max(1, len(snaps) // 1000)
        pts = snaps[::step]
        if snaps and pts[-1] is not snaps[-1]:
            pts.append(snaps[-1])
        return {
            "starting_balance": acct.starting_balance,
            "points": [{"at": p.at.isoformat(), "equity": p.equity, "cash": p.cash, "drawdown": p.drawdown} for p in pts],
        }


@router.get("/system-events")
async def get_system_events(request: Request, limit: int = Query(100, le=1000), min_level: str | None = None):
    levels = {"INFO": 0, "WARN": 1, "ERROR": 2, "CRITICAL": 3}
    with request.app.state.sessions() as s:
        q = select(SystemEventRow).order_by(SystemEventRow.id.desc())
        if min_level in levels:
            q = q.where(SystemEventRow.level.in_([k for k, v in levels.items() if v >= levels[min_level]]))
        rows = s.scalars(q.limit(limit)).all()
        return [
            {"at": _iso(e.at), "level": e.level, "code": e.code, "message": e.message, "event_id": e.event_id, "trade_uid": e.trade_uid}
            for e in rows
        ]
