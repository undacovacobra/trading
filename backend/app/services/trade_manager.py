"""The trading engine: one common path for every mode.

webhook event -> validation -> trade state -> option selection -> risk/sizing
-> TradeIntent -> executor (paper / dry-run / live)

The only thing that differs by mode is which Executor receives the intent.
"""

import asyncio
import logging
import uuid
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from app.executors.base import ExecutionResult, Executor
from app.executors.registry import ExecutorRegistry, ExecutorUnavailable
from app.market_data.base import MarketDataError, MarketDataProvider
from app.models import EventStatus, Trade, WebhookEvent
from app.schemas.config import OppositeEntryPolicy, SystemMode
from app.schemas.market import OptionQuote
from app.schemas.trade_intent import ExitIntent, TradeIntent
from app.schemas.tradingview import ENTRY_ACTIONS, EXIT_ACTIONS, REVERSE_ACTIONS, Action, TradingViewSignal
from app.services import config_store, paper_account
from app.services.notifier import Notifier
from app.services.option_selector import SelectionError, select_contract
from app.services.position_sizer import size_position
from app.services.risk_manager import RiskRejection, check_post_selection, check_pre_entry
from app.services.state_machine import OPEN_STATES, TradeState, transition
from app.services.symbols import futures_root

log = logging.getLogger("options_bridge.engine")

OPPOSITE = {"LONG": "SHORT", "SHORT": "LONG"}


@dataclass
class Outcome:
    status: str
    code: str
    message: str


@dataclass
class _Signal:
    """What the engine needs from a webhook event (or a synthetic dashboard action)."""

    event_id: str
    strategy_id: str
    symbol: str
    action: str
    signal_time: object = None
    received_at: object = None
    trade_id: str | None = None
    entry_price: float | None = None
    stop_price: float | None = None
    target_price: float | None = None
    risk_points: float | None = None
    futures_price: float | None = None
    reason: str | None = None
    signal_type: str | None = None

    @classmethod
    def from_event(cls, ev: WebhookEvent) -> "_Signal":
        p = TradingViewSignal.model_validate({**ev.payload, "secret": "-"})
        return cls(
            event_id=ev.event_id,
            strategy_id=p.strategy_id,
            symbol=p.symbol,
            action=p.action.value,
            signal_time=p.signal_time,
            received_at=ev.received_at,
            trade_id=p.trade_id,
            entry_price=p.entry_price,
            stop_price=p.stop_price,
            target_price=p.target_price,
            risk_points=p.risk_points,
            futures_price=p.futures_price,
            reason=p.reason,
            signal_type=(p.metadata or {}).get("signal_type"),
        )


class TradeEngine:
    def __init__(
        self,
        sessions: sessionmaker,
        market_data: MarketDataProvider,
        executors: ExecutorRegistry,
        clock,
        notifier: Notifier,
    ):
        self.sessions = sessions
        self.market_data = market_data
        self.executors = executors
        self.clock = clock
        self.notifier = notifier
        # Serialises all state-changing work: events, emergency close, mode changes.
        self.lock = asyncio.Lock()

    # ------------------------------------------------------------------ events
    async def process_event(self, event_pk: int) -> Outcome | None:
        async with self.lock:
            return await self._process_event(event_pk)

    async def _process_event(self, event_pk: int) -> Outcome | None:
        with self.sessions() as s:
            ev = s.get(WebhookEvent, event_pk)
            if ev is None or ev.status != EventStatus.RECEIVED:
                return None
            ev.status = EventStatus.PROCESSING
            ev.processing_started_at = self.clock.now()
            s.commit()
            event_id = ev.event_id
        log.info("processing %s event=%s", ev.action, event_id)

        try:
            with self.sessions() as s:
                ev = s.get(WebhookEvent, event_pk)
                outcome = await self._dispatch(s, _Signal.from_event(ev))
                ev.status, ev.result_code, ev.result_message = outcome.status, outcome.code, outcome.message
                ev.processed_at = self.clock.now()
                s.commit()
                return outcome
        except Exception as e:  # noqa: BLE001 -- recorded, never swallowed silently
            log.exception("event %s failed", event_id)
            with self.sessions() as s:
                ev = s.get(WebhookEvent, event_pk)
                ev.status, ev.result_code, ev.result_message = EventStatus.FAILED, "INTERNAL_ERROR", f"{type(e).__name__}: {e}"
                ev.processed_at = self.clock.now()
                self.notifier.emit(s, "ERROR", "INTERNAL_ERROR", f"Processing failed: {e}", event_id=event_id)
                s.commit()
            return Outcome(EventStatus.FAILED, "INTERNAL_ERROR", str(e))

    async def _dispatch(self, s: Session, sig: _Signal) -> Outcome:
        self.notifier.emit(s, "INFO", "SIGNAL_RECEIVED", f"{sig.action} {sig.symbol} ({sig.strategy_id})", event_id=sig.event_id)
        action = Action(sig.action)
        if action in ENTRY_ACTIONS:
            return await self._handle_entry(s, sig, ENTRY_ACTIONS[action])
        if action in EXIT_ACTIONS:
            return await self._handle_exit(s, sig, [EXIT_ACTIONS[action]], default_reason="SIGNAL_EXIT")
        if action == Action.CLOSE_ALL:
            return await self._handle_exit(s, sig, ["LONG", "SHORT"], default_reason="CLOSE_ALL")
        if action in REVERSE_ACTIONS:
            return await self._handle_reverse(s, sig, REVERSE_ACTIONS[action])
        return Outcome(EventStatus.IGNORED, "UNSUPPORTED_SIGNAL", f"Unsupported action {sig.action}")

    # ------------------------------------------------------------------ helpers
    def _find_open(self, s: Session, strategy_id: str, direction: str, tv_trade_id: str | None = None) -> list[Trade]:
        q = select(Trade).where(
            Trade.strategy_id == strategy_id, Trade.futures_direction == direction, Trade.state.in_(OPEN_STATES)
        )
        if tv_trade_id:
            q = q.where(Trade.tv_trade_id == tv_trade_id)
        return list(s.scalars(q.order_by(Trade.id)))

    async def _quote(self, contract_id: str, attempts: int = 3) -> OptionQuote | None:
        for i in range(attempts):
            try:
                q = (await self.market_data.get_quotes([contract_id])).get(contract_id)
                if q is not None and q.bid is not None:
                    return q
            except MarketDataError as e:
                log.warning("quote attempt %d for %s failed: %s", i + 1, contract_id, e)
            if i + 1 < attempts:
                await asyncio.sleep(0.25 * (i + 1))
        return None

    def _reject(self, s: Session, trade: Trade, code: str, message: str, *, risk_limit: bool = False, mode: str = "") -> Outcome:
        trade.reject_code, trade.reject_message = code, message
        transition(s, trade, TradeState.REJECTED, self.clock.now(), f"{code}: {message}")
        if risk_limit:
            self.notifier.risk(s, code, message, mode=mode, strategy_id=trade.strategy_id, event_id=trade.source_event_id)
        self.notifier.emit(
            s, "WARN", "TRADE_SKIPPED", f"{trade.futures_direction} {trade.strategy_id}: {code} - {message}",
            event_id=trade.source_event_id, trade_uid=trade.trade_uid,
        )
        return Outcome(EventStatus.IGNORED, code, message)

    # ------------------------------------------------------------------ entry
    async def _handle_entry(self, s: Session, sig: _Signal, direction: str, *, via_reversal: bool = False) -> Outcome:
        system = config_store.get_system(s)
        cfg = config_store.get_strategy(s, sig.strategy_id, auto_register=system.auto_register_strategies)
        if cfg is None:
            return Outcome(EventStatus.IGNORED, "UNKNOWN_STRATEGY", f"Strategy {sig.strategy_id!r} is not configured")
        if not cfg.enabled:
            return Outcome(EventStatus.IGNORED, "STRATEGY_DISABLED", f"Strategy {sig.strategy_id!r} is disabled")
        risk = config_store.get_risk(s)
        mode = system.mode.value
        now = self.clock.now()
        root = futures_root(sig.symbol)

        trade = Trade(
            trade_uid=uuid.uuid4().hex[:12],
            strategy_id=sig.strategy_id,
            mode=mode,
            state=None,
            source_event_id=sig.event_id,
            tv_trade_id=sig.trade_id,
            futures_symbol=sig.symbol,
            futures_root=root,
            futures_direction=direction,
            futures_entry=sig.entry_price if sig.entry_price is not None else sig.futures_price,
            futures_stop=sig.stop_price,
            futures_target=sig.target_price,
            risk_points=sig.risk_points,
            signal_type=sig.signal_type,
            signal_time=sig.signal_time,
            webhook_received_at=sig.received_at,
            expiration_mode=cfg.expiration_mode.value,
            strike_mode=cfg.strike_mode.value,
            sizing_mode=cfg.sizing_mode.value,
            created_at=now,
            updated_at=now,
            fees=0.0,
        )
        s.add(trade)
        transition(s, trade, TradeState.SIGNAL_RECEIVED, now, sig.action)
        s.flush()

        # Conflicting positions
        if self._find_open(s, sig.strategy_id, direction):
            return self._reject(s, trade, "ENTRY_WHILE_POSITION_EXISTS", f"A {direction} position is already open for {sig.strategy_id}")
        opposite = self._find_open(s, sig.strategy_id, OPPOSITE[direction])
        if opposite and not via_reversal:
            if cfg.opposite_entry_policy != OppositeEntryPolicy.REVERSE:
                return self._reject(s, trade, "CONFLICTING_POSITION", f"An opposite {OPPOSITE[direction]} position is open")
            results = await self._close_trades(s, opposite, sig, "REVERSAL")
            if not all(r.success for r in results):
                return self._reject(s, trade, "REVERSAL_ABORTED", "Could not confirm the opposite position closed")

        underlying = config_store.resolve_underlying(system, cfg, root)
        if not underlying:
            return self._reject(s, trade, "NO_OPTIONS_MAPPING", f"No options underlying configured for futures root {root}")
        trade.option_underlying = underlying

        try:
            executor: Executor = self.executors.get(mode)
        except ExecutorUnavailable as e:
            return self._reject(s, trade, e.code, e.message)

        try:
            check_pre_entry(s, system=system, risk=risk, mode=mode, now=now)
        except RiskRejection as r:
            return self._reject(s, trade, r.code, r.message, risk_limit=r.is_limit, mode=mode)

        transition(s, trade, TradeState.SELECTING_CONTRACT, self.clock.now(), f"{underlying} {cfg.expiration_mode.value}/{cfg.strike_mode.value}")
        try:
            sel = await select_contract(self.market_data, underlying=underlying, direction=direction, cfg=cfg, risk=risk, now=now)
        except SelectionError as e:
            trade.selection_notes = e.details.get("notes")
            return self._reject(s, trade, e.code, e.message)

        q = sel.quote
        trade.contract_selected_at = self.clock.now()
        trade.underlying_price_at_signal = sel.underlying_price
        trade.contract_id = sel.contract.contract_id
        trade.option_symbol = sel.contract.symbol
        trade.option_type = sel.option_type
        trade.strike = sel.contract.strike
        trade.expiration = sel.expiration
        trade.entry_bid, trade.entry_ask, trade.entry_mid, trade.entry_quote_time = q.bid, q.ask, q.mid, q.timestamp
        trade.entry_slippage = risk.entry_slippage
        trade.selection_notes = sel.notes or None

        limit_price = round(q.ask + risk.entry_slippage, 2)
        equity = await executor.get_account_equity(s)
        sizing = size_position(cfg, risk, limit_price, equity)
        trade.quantity = sizing.quantity
        if sizing.notes:
            trade.selection_notes = (trade.selection_notes or []) + sizing.notes
        log.info("calculated quantity = %d (%s)", sizing.quantity, cfg.sizing_mode.value)

        try:
            check_post_selection(
                s, risk=risk, mode=mode, now=now, expiration=sel.expiration, quantity=sizing.quantity,
                estimated_cost=sizing.estimated_cost, estimated_fees=sizing.estimated_fees,
                buying_power=await executor.get_buying_power(s), buying_power_code=executor.buying_power_code,
            )
        except RiskRejection as r:
            return self._reject(s, trade, r.code, r.message, risk_limit=r.is_limit, mode=mode)

        intent = TradeIntent(
            trade_id=trade.trade_uid,
            strategy_id=trade.strategy_id,
            mode=mode,
            direction=direction,
            options_underlying=underlying,
            option_type=sel.option_type,
            expiration=sel.expiration,
            strike=sel.contract.strike,
            quantity=sizing.quantity,
            contract_id=sel.contract.contract_id,
            option_symbol=sel.contract.symbol,
            estimated_bid=q.bid,
            estimated_ask=q.ask,
            estimated_mid=q.mid,
            quote_time=q.timestamp,
            underlying_price=sel.underlying_price,
            limit_price=limit_price,
            entry_slippage=risk.entry_slippage,
            estimated_cost=sizing.estimated_cost,
            estimated_fees=sizing.estimated_fees,
            maximum_cost=min(risk.max_trade_cost, sizing.budget or risk.max_trade_cost),
            expiration_mode=cfg.expiration_mode.value,
            strike_mode=cfg.strike_mode.value,
            sizing_mode=cfg.sizing_mode.value,
            source_event_id=sig.event_id,
        )
        trade.intent = intent.model_dump(mode="json")
        trade.execution_requested_at = self.clock.now()
        transition(s, trade, TradeState.READY_TO_SUBMIT, trade.execution_requested_at, f"{intent.quantity} x {sel.contract.label} @ {limit_price:.2f}")

        result = await executor.open_position(s, trade, intent)
        if result.dry_run:
            msg = f"WOULD BUY {intent.quantity} {sel.contract.label} @ {limit_price:.2f} (est. ${intent.estimated_cost:,.2f})"
            self.notifier.emit(s, "INFO", "DRY_RUN_INTENT", msg, event_id=sig.event_id, trade_uid=trade.trade_uid)
            return Outcome(EventStatus.PROCESSED, "DRY_RUN", msg)
        if not result.success:
            self.notifier.emit(
                s, "WARN", "TRADE_SKIPPED", f"{result.error_code}: {result.error_message}", event_id=sig.event_id, trade_uid=trade.trade_uid
            )
            return Outcome(EventStatus.IGNORED, result.error_code or "ENTRY_FAILED", result.error_message or "")
        label = "PAPER_TRADE_OPENED" if mode == SystemMode.OBSERVE.value else "LIVE_ORDER_FILLED"
        msg = f"Opened {trade.quantity} {sel.contract.label} @ {result.fill_price:.2f}"
        self.notifier.emit(s, "INFO", label, msg, event_id=sig.event_id, trade_uid=trade.trade_uid)
        return Outcome(EventStatus.PROCESSED, "OPENED", msg)

    # ------------------------------------------------------------------ exits
    async def _close_trades(self, s: Session, trades: list[Trade], sig: _Signal, reason: str):
        results = []
        risk = config_store.get_risk(s)
        for t in trades:
            try:
                executor = self.executors.get(t.mode)
            except ExecutorUnavailable as e:
                self.notifier.emit(s, "CRITICAL", e.code, f"Cannot close {t.trade_uid} ({t.mode}): {e.message}", event_id=sig.event_id)
                results.append(_fail(e.code, e.message))
                continue
            t.exit_event_id = sig.event_id
            t.exit_signal_time = sig.signal_time
            t.exit_received_at = sig.received_at
            t.futures_exit = sig.futures_price if sig.futures_price is not None else sig.entry_price
            t.exit_reason = reason
            q = await self._quote(t.contract_id)
            if q is None:
                msg = f"No quote for {t.option_symbol}; position left OPEN"
                self.notifier.emit(s, "CRITICAL", "QUOTE_UNAVAILABLE", msg, event_id=sig.event_id, trade_uid=t.trade_uid)
                results.append(_fail("QUOTE_UNAVAILABLE", msg))
                continue
            intent = ExitIntent(
                trade_id=t.trade_uid,
                contract_id=t.contract_id,
                quantity=t.quantity,
                bid=q.bid,
                ask=q.ask,
                mid=q.mid,
                quote_time=q.timestamp,
                limit_price=round(max(0.0, q.bid - risk.exit_slippage), 2),
                exit_slippage=risk.exit_slippage,
                fee_per_contract=risk.fee_per_contract,
                reason=reason,
                source_event_id=sig.event_id,
            )
            res = await executor.close_position(s, t, intent)
            if res.success:
                code = "PAPER_TRADE_CLOSED" if t.mode == SystemMode.OBSERVE.value else "LIVE_TRADE_CLOSED"
                self.notifier.emit(
                    s, "INFO", code,
                    f"Closed {t.quantity} {t.option_symbol} @ {res.fill_price:.2f} P&L {t.realized_pnl:+.2f} ({reason})",
                    event_id=sig.event_id, trade_uid=t.trade_uid,
                )
            else:
                self.notifier.emit(
                    s, "CRITICAL", res.error_code or "EXIT_FAILED", f"Exit failed for {t.trade_uid}: {res.error_message}",
                    event_id=sig.event_id, trade_uid=t.trade_uid,
                )
            results.append(res)
        return results

    async def _handle_exit(self, s: Session, sig: _Signal, directions: list[str], *, default_reason: str) -> Outcome:
        trades = [t for d in directions for t in self._find_open(s, sig.strategy_id, d, sig.trade_id)]
        if not trades:
            msg = f"No open {'/'.join(directions)} position for {sig.strategy_id}" + (f" trade_id={sig.trade_id}" if sig.trade_id else "")
            self.notifier.emit(s, "WARN", "EXIT_WITHOUT_POSITION", msg, event_id=sig.event_id)
            return Outcome(EventStatus.IGNORED, "EXIT_WITHOUT_POSITION", msg)
        reason = (sig.reason or default_reason).upper()
        results = await self._close_trades(s, trades, sig, reason)
        if all(r.success for r in results):
            return Outcome(EventStatus.PROCESSED, "CLOSED", f"Closed {len(trades)} position(s) ({reason})")
        failed = [r for r in results if not r.success]
        return Outcome(EventStatus.FAILED, failed[0].error_code or "EXIT_FAILED", failed[0].error_message or "")

    async def _handle_reverse(self, s: Session, sig: _Signal, new_direction: str) -> Outcome:
        existing = self._find_open(s, sig.strategy_id, OPPOSITE[new_direction], sig.trade_id)
        closed_msg = ""
        if existing:
            results = await self._close_trades(s, existing, sig, (sig.reason or "REVERSAL").upper())
            if not all(r.success for r in results):
                bad = next(r for r in results if not r.success)
                msg = f"Reversal aborted: could not close existing {OPPOSITE[new_direction]} ({bad.error_code})"
                self.notifier.emit(s, "CRITICAL", "REVERSAL_ABORTED", msg, event_id=sig.event_id)
                return Outcome(EventStatus.FAILED, "REVERSAL_ABORTED", msg)
            closed_msg = f"Closed {len(existing)} {OPPOSITE[new_direction]}; "
        else:
            cfg = config_store.get_strategy(s, sig.strategy_id)
            if cfg is not None and not cfg.reverse_when_flat:
                return Outcome(EventStatus.IGNORED, "REVERSE_WHEN_FLAT_DISABLED", "No position to reverse")
        out = await self._handle_entry(s, sig, new_direction, via_reversal=True)
        # Closing the old side is an action taken even if the new entry was then rejected.
        return Outcome(EventStatus.PROCESSED if existing else out.status, out.code, closed_msg + out.message)

    # ------------------------------------------------------------------ controls
    async def emergency_close(self, reason: str = "EMERGENCY") -> dict:
        """Pause new entries, then close every position this application owns."""
        async with self.lock:
            with self.sessions() as s:
                system = config_store.get_system(s)
                system.paused = True
                config_store.save_system(s, system)
                trades = list(s.scalars(select(Trade).where(Trade.state.in_(OPEN_STATES)).order_by(Trade.id)))
                now = self.clock.now()
                sig = _Signal(
                    event_id=f"emergency-{now:%Y%m%dT%H%M%S}-{uuid.uuid4().hex[:6]}", strategy_id="*", symbol="*",
                    action="CLOSE_ALL", received_at=now, reason=reason,
                )
                self.notifier.emit(s, "CRITICAL", "EMERGENCY_CLOSE", f"Emergency close requested for {len(trades)} position(s)")
                results = await self._close_trades(s, trades, sig, reason)
                s.commit()
                return {
                    "requested": len(trades),
                    "closed": sum(1 for r in results if r.success),
                    "failed": [{"code": r.error_code, "message": r.error_message} for r in results if not r.success],
                }

    async def mark_to_market(self) -> int:
        """Refresh quotes for open positions and snapshot paper equity."""
        with self.sessions() as s:
            rows = s.execute(select(Trade.id, Trade.contract_id).where(Trade.state.in_(OPEN_STATES))).all()
        if not rows:
            return 0  # fills already snapshot equity; nothing to mark
        quotes = {}
        if rows:
            try:
                quotes = await self.market_data.get_quotes(sorted({r.contract_id for r in rows}))
            except MarketDataError as e:
                log.warning("mark-to-market quote refresh failed: %s", e)
        async with self.lock:
            with self.sessions() as s:
                for r in rows:
                    t = s.get(Trade, r.id)
                    q = quotes.get(r.contract_id)
                    if t is None or t.state not in OPEN_STATES or q is None:
                        continue
                    t.current_bid, t.current_ask, t.current_quote_time = q.bid, q.ask, q.timestamp
                system = config_store.get_system(s)
                acct = paper_account.get_active_account(s, starting_balance=system.paper_starting_balance, now=self.clock.now())
                paper_account.snapshot(s, acct, self.clock.now())
                s.commit()
        return len(rows)


def _fail(code: str, message: str) -> ExecutionResult:
    return ExecutionResult(False, error_code=code, error_message=message)
