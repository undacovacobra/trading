"""Read-only market data interface.

This interface deliberately has no order methods. The PaperExecutor and the
option selector only ever receive a MarketDataProvider, so nothing in the
OBSERVE path can reach order placement.
"""

from abc import ABC, abstractmethod
from datetime import date

from app.schemas.market import OptionContract, OptionQuote, UnderlyingQuote


class MarketDataError(RuntimeError):
    code = "MARKET_DATA_UNAVAILABLE"


class OptionChainUnavailable(MarketDataError):
    code = "OPTION_CHAIN_UNAVAILABLE"


class MarketDataProvider(ABC):
    name: str = "base"
    is_simulated: bool = False

    @abstractmethod
    async def get_underlying_price(self, symbol: str) -> UnderlyingQuote: ...

    @abstractmethod
    async def get_expirations(self, symbol: str) -> list[date]: ...

    @abstractmethod
    async def get_chain(self, symbol: str, expiration: date, option_type: str) -> list[OptionContract]: ...

    @abstractmethod
    async def get_quotes(self, contract_ids: list[str]) -> dict[str, OptionQuote]: ...

    async def close(self) -> None:
        return None
