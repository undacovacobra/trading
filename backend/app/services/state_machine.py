"""Explicit trade state machine. Every transition is validated and persisted."""

import logging
from datetime import datetime

from sqlalchemy.orm import Session

from app.models import Trade, TradeStateTransition

log = logging.getLogger("options_bridge.trade")


class TradeState:
    SIGNAL_RECEIVED = "SIGNAL_RECEIVED"
    SELECTING_CONTRACT = "SELECTING_CONTRACT"
    READY_TO_SUBMIT = "READY_TO_SUBMIT"
    # paper
    SIMULATED_ENTRY = "SIMULATED_ENTRY"
    SIMULATED_EXIT = "SIMULATED_EXIT"
    # live
    ORDER_SUBMITTED = "ORDER_SUBMITTED"
    PARTIALLY_FILLED = "PARTIALLY_FILLED"
    EXIT_REQUESTED = "EXIT_REQUESTED"
    EXIT_ORDER_SUBMITTED = "EXIT_ORDER_SUBMITTED"
    # common
    OPEN = "OPEN"
    CLOSED = "CLOSED"
    REJECTED = "REJECTED"
    ERROR = "ERROR"
    DRY_RUN_COMPLETE = "DRY_RUN_COMPLETE"


S = TradeState

TERMINAL = frozenset({S.CLOSED, S.REJECTED, S.ERROR, S.DRY_RUN_COMPLETE})
OPEN_STATES = frozenset({S.OPEN, S.PARTIALLY_FILLED})
# States in which the position holds (or may hold) contracts at the broker.
HOLDING_STATES = frozenset({S.OPEN, S.PARTIALLY_FILLED, S.EXIT_REQUESTED, S.EXIT_ORDER_SUBMITTED, S.SIMULATED_EXIT})

ALLOWED: dict[str | None, frozenset[str]] = {
    None: frozenset({S.SIGNAL_RECEIVED}),
    S.SIGNAL_RECEIVED: frozenset({S.SELECTING_CONTRACT, S.REJECTED}),
    S.SELECTING_CONTRACT: frozenset({S.READY_TO_SUBMIT, S.REJECTED}),
    S.READY_TO_SUBMIT: frozenset({S.SIMULATED_ENTRY, S.ORDER_SUBMITTED, S.DRY_RUN_COMPLETE, S.REJECTED}),
    S.SIMULATED_ENTRY: frozenset({S.OPEN}),
    S.ORDER_SUBMITTED: frozenset({S.PARTIALLY_FILLED, S.OPEN, S.REJECTED}),
    S.PARTIALLY_FILLED: frozenset({S.OPEN, S.EXIT_REQUESTED}),
    S.OPEN: frozenset({S.SIMULATED_EXIT, S.EXIT_REQUESTED}),
    S.SIMULATED_EXIT: frozenset({S.CLOSED}),
    S.EXIT_REQUESTED: frozenset({S.EXIT_ORDER_SUBMITTED, S.OPEN}),
    S.EXIT_ORDER_SUBMITTED: frozenset({S.CLOSED, S.OPEN}),
    S.CLOSED: frozenset(),
    S.REJECTED: frozenset(),
    S.ERROR: frozenset(),
    S.DRY_RUN_COMPLETE: frozenset(),
}


class InvalidTransition(RuntimeError):
    pass


def transition(session: Session, trade: Trade, to: str, at: datetime, note: str | None = None) -> None:
    current = trade.state
    allowed = ALLOWED.get(current, frozenset())
    # ERROR is reachable from any non-terminal state.
    if to not in allowed and not (to == S.ERROR and current not in TERMINAL):
        raise InvalidTransition(f"{trade.trade_uid}: {current} -> {to} is not allowed")
    session.add(TradeStateTransition(trade=trade, from_state=current, to_state=to, at=at, note=note))
    trade.state = to
    trade.updated_at = at
    if to in (S.CLOSED, S.REJECTED, S.ERROR, S.DRY_RUN_COMPLETE):
        trade.closed_at = trade.closed_at or at
    log.info("trade %s %s -> %s%s", trade.trade_uid, current, to, f" ({note})" if note else "")
