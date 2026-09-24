"""Synthetic option chain for development and automated tests.

Prices come from Black-Scholes with a flat IV, with a bid/ask spread around the
theoretical value. This is NOT real market data: the dashboard shows a
"SIMULATED MARKET DATA" warning whenever this provider is active, and paper
results produced with it say nothing about real option economics.
"""

import math
import random
import re
from datetime import date, datetime, time

from app.clock import NY, Clock
from app.market_data.base import MarketDataError, MarketDataProvider
from app.schemas.market import OptionContract, OptionQuote, UnderlyingQuote
from app.services.market_calendar import is_trading_day, next_trading_day, session_close

_OCC = re.compile(r"([A-Z]{1,6})(\d{6})([CP])(\d{8})")

DEFAULT_PRICES = {"QQQ": 603.72, "SPY": 661.40, "IWM": 243.15}


def occ_symbol(underlying: str, expiration: date, option_type: str, strike: float) -> str:
    return f"{underlying}{expiration:%y%m%d}{option_type[0]}{round(strike * 1000):08d}"


def parse_occ(symbol: str) -> tuple[str, date, str, float]:
    m = _OCC.fullmatch(symbol)
    if not m:
        raise MarketDataError(f"Unrecognised contract id {symbol!r}")
    root, ymd, cp, strike = m.groups()
    exp = datetime.strptime(ymd, "%y%m%d").date()
    return root, exp, "CALL" if cp == "C" else "PUT", int(strike) / 1000


def _norm_cdf(x: float) -> float:
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def black_scholes(spot: float, strike: float, years: float, iv: float, option_type: str, rate: float = 0.04):
    """Returns (price, delta)."""
    if years <= 0:
        intrinsic = max(0.0, spot - strike) if option_type == "CALL" else max(0.0, strike - spot)
        delta = (1.0 if spot > strike else 0.0) if option_type == "CALL" else (-1.0 if spot < strike else 0.0)
        return intrinsic, delta
    sd = iv * math.sqrt(years)
    d1 = (math.log(spot / strike) + (rate + iv * iv / 2) * years) / sd
    d2 = d1 - sd
    if option_type == "CALL":
        return spot * _norm_cdf(d1) - strike * math.exp(-rate * years) * _norm_cdf(d2), _norm_cdf(d1)
    return strike * math.exp(-rate * years) * _norm_cdf(-d2) - spot * _norm_cdf(-d1), _norm_cdf(d1) - 1


class SimulatedMarketData(MarketDataProvider):
    name = "simulated"
    is_simulated = True

    def __init__(
        self,
        clock: Clock,
        prices: dict[str, float] | None = None,
        iv: float = 0.20,
        spread_pct: float = 2.0,
        walk: bool = False,
        strike_width: int = 40,
        expirations_ahead: int = 15,
        seed: int | None = None,
    ):
        self.clock = clock
        self._prices = dict(prices or DEFAULT_PRICES)
        self._iv = iv
        self._spread_pct = spread_pct
        self._walk = walk
        self._last_walk: dict[str, datetime] = {}
        self._rng = random.Random(seed)
        self._strike_width = strike_width
        self._expirations_ahead = expirations_ahead
        self._overrides: dict[str, dict] = {}
        self.fail_next: MarketDataError | None = None  # test hook

    # -- test / demo controls -------------------------------------------------
    def set_underlying(self, symbol: str, price: float) -> None:
        self._prices[symbol.upper()] = price

    def set_quote_override(self, contract_id: str, **fields) -> None:
        """Force bid/ask/delta/open_interest/volume for one contract."""
        self._overrides[contract_id] = fields

    def clear_overrides(self) -> None:
        self._overrides.clear()

    # -- provider API ---------------------------------------------------------
    def _check_fail(self):
        if self.fail_next is not None:
            err, self.fail_next = self.fail_next, None
            raise err

    def _price(self, symbol: str) -> float:
        symbol = symbol.upper()
        if symbol not in self._prices:
            raise MarketDataError(f"No simulated price for {symbol}")
        if self._walk:
            now = self.clock.now()
            last = self._last_walk.get(symbol, now)
            steps = int((now - last).total_seconds() // 5)
            if steps > 0 or symbol not in self._last_walk:
                p = self._prices[symbol]
                for _ in range(min(steps, 720)):
                    p *= math.exp(self._rng.gauss(0, 0.0004))
                self._prices[symbol] = round(p, 2)
                self._last_walk[symbol] = now
        return self._prices[symbol]

    async def get_underlying_price(self, symbol: str) -> UnderlyingQuote:
        self._check_fail()
        return UnderlyingQuote(symbol.upper(), self._price(symbol), self.clock.now())

    async def get_expirations(self, symbol: str) -> list[date]:
        self._check_fail()
        self._price(symbol)
        today = self.clock.now().astimezone(NY).date()
        d = today if is_trading_day(today) else next_trading_day(today)
        out = [d]
        while len(out) < self._expirations_ahead:
            out.append(next_trading_day(out[-1]))
        return out

    async def get_chain(self, symbol: str, expiration: date, option_type: str) -> list[OptionContract]:
        self._check_fail()
        symbol = symbol.upper()
        center = round(self._price(symbol))
        strikes = range(center - self._strike_width, center + self._strike_width + 1)
        return [
            OptionContract(
                contract_id=occ_symbol(symbol, expiration, option_type, k),
                symbol=occ_symbol(symbol, expiration, option_type, k),
                underlying=symbol,
                option_type=option_type,
                strike=float(k),
                expiration=expiration,
            )
            for k in strikes
            if k > 0
        ]

    async def get_quotes(self, contract_ids: list[str]) -> dict[str, OptionQuote]:
        self._check_fail()
        now = self.clock.now()
        out = {}
        for cid in contract_ids:
            root, exp, typ, strike = parse_occ(cid)
            close = session_close(exp) or time(16)
            expiry_dt = datetime.combine(exp, close, tzinfo=NY)
            years = max((expiry_dt - now).total_seconds(), 0) / (365 * 24 * 3600)
            theo, delta = black_scholes(self._price(root), strike, years, self._iv, typ)
            half = max(0.01, theo * self._spread_pct / 200)
            bid = round(max(0.0, theo - half), 2)
            ask = round(max(0.01, theo + half), 2)
            q = dict(bid=bid, ask=ask, delta=round(delta, 4), open_interest=1500, volume=800)
            q.update(self._overrides.get(cid, {}))
            out[cid] = OptionQuote(
                contract_id=cid,
                bid=q["bid"],
                ask=q["ask"],
                timestamp=now,
                last=round(theo, 2),
                delta=q["delta"],
                implied_volatility=self._iv,
                open_interest=q["open_interest"],
                volume=q["volume"],
            )
        return out

