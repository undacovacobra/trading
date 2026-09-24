"""DRY_RUN: show exactly what would be done, create no position (paper or live)."""

from sqlalchemy.orm import Session

from app.executors.base import ExecutionResult, Executor
from app.executors.paper import PaperExecutor
from app.models import Trade
from app.schemas.config import SystemMode
from app.schemas.trade_intent import ExitIntent, TradeIntent
from app.services.state_machine import TradeState, transition


class DryRunExecutor(Executor):
    mode = SystemMode.DRY_RUN.value

    def __init__(self, clock, paper: PaperExecutor):
        self.clock = clock
        self._paper = paper  # only used to read equity for %-of-equity sizing

    async def get_account_equity(self, session: Session) -> float:
        return await self._paper.get_account_equity(session)

    async def get_buying_power(self, session: Session) -> float:
        return await self._paper.get_buying_power(session)

    async def get_position(self, session: Session, trade: Trade) -> dict:
        return {"trade_uid": trade.trade_uid, "state": trade.state, "dry_run": True}

    def account_label(self) -> str:
        return "Dry run (no positions)"

    async def open_position(self, session: Session, trade: Trade, intent: TradeIntent) -> ExecutionResult:
        note = f"WOULD BUY {intent.quantity} {intent.option_symbol} @ {intent.limit_price:.2f} (est. ${intent.estimated_cost:,.2f})"
        transition(session, trade, TradeState.DRY_RUN_COMPLETE, self.clock.now(), note)
        return ExecutionResult(True, dry_run=True)

    async def close_position(self, session: Session, trade: Trade, intent: ExitIntent) -> ExecutionResult:
        # DRY_RUN never opens positions, so there is never anything to close.
        return ExecutionResult(False, error_code="NO_POSITION", error_message="dry-run trades hold no position")
