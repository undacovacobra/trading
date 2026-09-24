from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel


class TradeIntent(BaseModel):
    """Broker-independent description of an entry. PaperExecutor and
    RobinhoodExecutor consume this exact object (section 48)."""

    trade_id: str
    strategy_id: str
    mode: str
    direction: Literal["LONG", "SHORT"]
    options_underlying: str
    option_type: Literal["CALL", "PUT"]
    expiration: date
    strike: float
    quantity: int
    contract_id: str
    option_symbol: str
    estimated_bid: float
    estimated_ask: float
    estimated_mid: float
    quote_time: datetime
    underlying_price: float
    # Marketable limit: ask + configured entry slippage. The paper fill uses the same price.
    limit_price: float
    entry_slippage: float
    estimated_cost: float
    estimated_fees: float
    maximum_cost: float
    expiration_mode: str
    strike_mode: str
    sizing_mode: str
    source_event_id: str


class ExitIntent(BaseModel):
    trade_id: str
    contract_id: str
    quantity: int
    bid: float | None
    ask: float | None
    mid: float | None
    quote_time: datetime
    # Marketable limit for a sell: bid - configured exit slippage (floored at 0).
    limit_price: float
    exit_slippage: float
    fee_per_contract: float
    reason: str
    source_event_id: str
