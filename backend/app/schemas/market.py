from dataclasses import dataclass
from datetime import date, datetime


@dataclass(frozen=True)
class OptionContract:
    contract_id: str  # broker instrument id (simulated: OCC symbol)
    symbol: str  # human/OCC symbol, e.g. QQQ260924C00604000
    underlying: str
    option_type: str  # CALL / PUT
    strike: float
    expiration: date

    @property
    def label(self) -> str:
        return f"{self.underlying} {self.strike:g}{self.option_type[0]} {self.expiration:%b %d}"


@dataclass(frozen=True)
class OptionQuote:
    contract_id: str
    bid: float | None
    ask: float | None
    timestamp: datetime
    last: float | None = None
    delta: float | None = None
    implied_volatility: float | None = None
    open_interest: int | None = None
    volume: int | None = None

    @property
    def mid(self) -> float | None:
        if self.bid is None or self.ask is None:
            return None
        return round((self.bid + self.ask) / 2, 4)

    @property
    def spread(self) -> float | None:
        if self.bid is None or self.ask is None:
            return None
        return round(self.ask - self.bid, 4)

    @property
    def spread_pct(self) -> float | None:
        mid = self.mid
        if not mid:
            return None
        return self.spread / mid * 100


@dataclass(frozen=True)
class UnderlyingQuote:
    symbol: str
    price: float
    timestamp: datetime
