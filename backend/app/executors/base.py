"""Common execution interface. The trade engine never knows which one is active."""

from abc import ABC, abstractmethod
from dataclasses import dataclass

from sqlalchemy.orm import Session

from app.models import Trade
from app.schemas.trade_intent import ExitIntent, TradeIntent


@dataclass
class ExecutionResult:
    success: bool
    filled_quantity: int = 0
    fill_price: float | None = None
    error_code: str | None = None
    error_message: str | None = None
    dry_run: bool = False


class Executor(ABC):
    mode: str
    buying_power_code: str = "INSUFFICIENT_BUYING_POWER"

    @abstractmethod
    async def open_position(self, session: Session, trade: Trade, intent: TradeIntent) -> ExecutionResult:
        """Execute an entry. Responsible for advancing the trade state from READY_TO_SUBMIT."""

    @abstractmethod
    async def close_position(self, session: Session, trade: Trade, intent: ExitIntent) -> ExecutionResult:
        """Close exactly this trade's contracts. Advances the trade state from OPEN."""

    @abstractmethod
    async def get_position(self, session: Session, trade: Trade) -> dict: ...

    @abstractmethod
    async def get_account_equity(self, session: Session) -> float: ...

    @abstractmethod
    async def get_buying_power(self, session: Session) -> float: ...

    def account_label(self) -> str:
        return self.mode
