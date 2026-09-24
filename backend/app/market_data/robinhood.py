"""Real option chains/quotes from Robinhood's Agentic MCP (read-only tools only).

Holds a RobinhoodReadClient, which cannot call order tools. The argument and
response mappings must be written from the inspected MCP schemas
(see app/brokers/robinhood_mcp.py); until then each method raises
SchemaNotVerified and the trade is skipped with that error code rather than
guessing at parameters.
"""

from datetime import date

from app.brokers.robinhood_mcp import RobinhoodReadClient, SchemaNotVerified
from app.market_data.base import MarketDataError, MarketDataProvider
from app.schemas.market import OptionContract, OptionQuote, UnderlyingQuote


class RobinhoodMarketData(MarketDataProvider):
    name = "robinhood"
    is_simulated = False

    def __init__(self, client: RobinhoodReadClient):
        self.client = client

    async def _unmapped(self, what: str):
        err = MarketDataError(f"Robinhood {what} mapping not implemented yet: inspect MCP schemas first")
        err.code = SchemaNotVerified.code
        raise err

    async def get_underlying_price(self, symbol: str) -> UnderlyingQuote:
        await self._unmapped("underlying quote")

    async def get_expirations(self, symbol: str) -> list[date]:
        await self._unmapped("get_option_chains (expirations)")

    async def get_chain(self, symbol: str, expiration: date, option_type: str) -> list[OptionContract]:
        await self._unmapped("get_option_instruments")

    async def get_quotes(self, contract_ids: list[str]) -> dict[str, OptionQuote]:
        await self._unmapped("get_option_quotes")

    async def close(self) -> None:
        await self.client.close()
