"""Option-selection engine: expiration, strike, liquidity filters.

Identical in every mode -- OBSERVE tells you exactly what LIVE would have
attempted. If the configured contract fails the filters the trade is skipped
(NO_ACCEPTABLE_CONTRACT); a different contract is only used when a fallback
policy is explicitly configured.
"""

import logging
from dataclasses import dataclass, field
from datetime import date, datetime

from app.market_data.base import MarketDataError, MarketDataProvider, OptionChainUnavailable
from app.schemas.config import ExpirationMode, FallbackPolicy, RiskConfig, StrategyConfig, StrikeMode
from app.schemas.market import OptionContract, OptionQuote
from app.services.market_calendar import is_trading_day, nth_trading_day, ny_date, trading_days_until

log = logging.getLogger("options_bridge.selector")


class SelectionError(RuntimeError):
    def __init__(self, code: str, message: str, details: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}


@dataclass
class Selection:
    contract: OptionContract
    quote: OptionQuote
    underlying: str
    underlying_price: float
    expiration: date
    option_type: str
    dte: int
    fallback_used: bool = False
    notes: list[str] = field(default_factory=list)


def option_type_for(direction: str, cfg: StrategyConfig) -> str:
    return cfg.long_option_type if direction == "LONG" else cfg.short_option_type


def choose_expiration(expirations: list[date], cfg: StrategyConfig, today: date) -> date:
    exps = sorted(e for e in set(expirations) if e >= today)
    if not exps:
        raise SelectionError("NO_EXPIRATION", "No listed expirations on or after today")
    mode = cfg.expiration_mode
    if mode == ExpirationMode.SAME_DAY:
        if not is_trading_day(today) or today not in exps:
            raise SelectionError("NO_EXPIRATION", f"No same-day (0DTE) expiration listed for {today}")
        return today
    if mode == ExpirationMode.NEXT_AVAILABLE:
        return exps[0]
    if mode == ExpirationMode.EXACT_DTE:
        target = nth_trading_day(today, cfg.dte)
        if target not in exps:
            raise SelectionError("NO_EXPIRATION", f"No expiration exactly {cfg.dte} trading day(s) out ({target})")
        return target
    if mode == ExpirationMode.MIN_DTE:
        for e in exps:
            if trading_days_until(today, e) >= cfg.dte:
                return e
        raise SelectionError("NO_EXPIRATION", f"No expiration at least {cfg.dte} trading day(s) out")
    raise SelectionError("INVALID_CONFIG", f"Unknown expiration mode {mode}")


def _rank_by_strike(chain: list[OptionContract], target: float) -> list[OptionContract]:
    return sorted(chain, key=lambda c: (abs(c.strike - target), c.strike))


def rank_contracts(
    chain: list[OptionContract],
    quotes: dict[str, OptionQuote] | None,
    cfg: StrategyConfig,
    option_type: str,
    spot: float,
) -> list[OptionContract]:
    """Order candidates best-first according to the strike-selection mode."""
    sign = 1 if option_type == "CALL" else -1  # OTM direction
    mode = cfg.strike_mode
    if mode == StrikeMode.ATM:
        return _rank_by_strike(chain, spot)
    if mode == StrikeMode.OTM_PERCENT:
        return _rank_by_strike(chain, spot * (1 + sign * cfg.otm_percent / 100))
    if mode == StrikeMode.FIXED_OFFSET:
        return _rank_by_strike(chain, spot + sign * cfg.strike_offset)
    if mode == StrikeMode.OTM_STRIKES:
        strikes = sorted({c.strike for c in chain})
        atm_idx = min(range(len(strikes)), key=lambda i: (abs(strikes[i] - spot), strikes[i]))
        target_idx = max(0, min(len(strikes) - 1, atm_idx + sign * cfg.otm_strikes))
        index = {k: i for i, k in enumerate(strikes)}
        return sorted(chain, key=lambda c: (abs(index[c.strike] - target_idx), c.strike))
    if mode == StrikeMode.DELTA:
        with_delta = [c for c in chain if quotes and quotes.get(c.contract_id) and quotes[c.contract_id].delta is not None]
        if not with_delta:
            raise SelectionError("GREEKS_UNAVAILABLE", "DELTA mode selected but no usable delta values in the chain")
        return sorted(with_delta, key=lambda c: (abs(abs(quotes[c.contract_id].delta) - cfg.target_delta), c.strike))
    if mode == StrikeMode.PREMIUM:
        priced = [c for c in chain if quotes and quotes.get(c.contract_id) and quotes[c.contract_id].ask]
        if not priced:
            raise SelectionError("NO_QUOTE", "PREMIUM mode selected but no option asks available")
        return sorted(priced, key=lambda c: (abs(quotes[c.contract_id].ask - cfg.target_premium), c.strike))
    raise SelectionError("INVALID_CONFIG", f"Unknown strike mode {mode}")


def check_filters(quote: OptionQuote | None, cfg: StrategyConfig, risk: RiskConfig) -> tuple[str, str] | None:
    """Returns (code, message) for the first violated liquidity rule, or None."""
    f = cfg.filters
    if quote is None or quote.bid is None or quote.ask is None:
        return "NO_QUOTE", "No bid/ask available"
    if quote.ask <= 0 or quote.ask < quote.bid:
        return "INVALID_QUOTE", f"Invalid quote bid={quote.bid} ask={quote.ask}"
    if quote.bid < f.min_bid:
        return "BID_TOO_LOW", f"bid {quote.bid:.2f} < minimum {f.min_bid:.2f}"
    if quote.ask > f.max_ask:
        return "ASK_TOO_HIGH", f"ask {quote.ask:.2f} > maximum {f.max_ask:.2f}"
    max_spread = min(f.max_spread_pct, risk.max_spread_pct)
    if quote.spread_pct is None or quote.spread_pct > max_spread:
        return "SPREAD_TOO_WIDE", f"spread {quote.spread_pct or 0:.1f}% > maximum {max_spread:.1f}%"
    if f.min_open_interest is not None and (quote.open_interest or 0) < f.min_open_interest:
        return "OPEN_INTEREST_TOO_LOW", f"open interest {quote.open_interest} < {f.min_open_interest}"
    if f.min_volume is not None and (quote.volume or 0) < f.min_volume:
        return "VOLUME_TOO_LOW", f"volume {quote.volume} < {f.min_volume}"
    return None


async def select_contract(
    provider: MarketDataProvider,
    *,
    underlying: str,
    direction: str,
    cfg: StrategyConfig,
    risk: RiskConfig,
    now: datetime,
) -> Selection:
    option_type = option_type_for(direction, cfg)
    today = ny_date(now)
    notes: list[str] = []
    try:
        spot_q = await provider.get_underlying_price(underlying)
        expirations = await provider.get_expirations(underlying)
    except MarketDataError as e:
        raise SelectionError(getattr(e, "code", "MARKET_DATA_UNAVAILABLE"), str(e)) from e
    expiration = choose_expiration(expirations, cfg, today)
    try:
        chain = await provider.get_chain(underlying, expiration, option_type)
    except MarketDataError as e:
        raise SelectionError(getattr(e, "code", OptionChainUnavailable.code), str(e)) from e
    chain = [c for c in chain if c.option_type == option_type and c.expiration == expiration]
    if not chain:
        raise SelectionError("OPTION_CHAIN_UNAVAILABLE", f"Empty {option_type} chain for {underlying} {expiration}")

    spot = spot_q.price
    quotes: dict[str, OptionQuote] | None = None
    if cfg.strike_mode in (StrikeMode.DELTA, StrikeMode.PREMIUM):
        # Limit quote requests to strikes within 15% of spot.
        near = [c for c in chain if abs(c.strike - spot) <= spot * 0.15]
        try:
            quotes = await provider.get_quotes([c.contract_id for c in near])
        except MarketDataError as e:
            raise SelectionError(getattr(e, "code", "MARKET_DATA_UNAVAILABLE"), str(e)) from e
        chain = near

    ranked = rank_contracts(chain, quotes, cfg, option_type, spot)
    max_candidates = 1 + (cfg.fallback_max_steps if cfg.fallback_policy == FallbackPolicy.NEAREST_PASSING else 0)
    candidates = ranked[:max_candidates]

    # Fresh quotes for the candidates we may actually trade.
    try:
        fresh = await provider.get_quotes([c.contract_id for c in candidates])
    except MarketDataError as e:
        raise SelectionError(getattr(e, "code", "MARKET_DATA_UNAVAILABLE"), str(e)) from e

    first_failure = None
    for i, c in enumerate(candidates):
        q = fresh.get(c.contract_id)
        failure = check_filters(q, cfg, risk)
        if failure is None:
            if i > 0:
                notes.append(f"fallback: primary {candidates[0].label} rejected ({first_failure[1]}); using {c.label}")
            log.info("selected %s bid %.2f ask %.2f (spot %.2f)", c.label, q.bid, q.ask, spot)
            return Selection(
                contract=c,
                quote=q,
                underlying=underlying,
                underlying_price=spot,
                expiration=expiration,
                option_type=option_type,
                dte=trading_days_until(today, expiration),
                fallback_used=i > 0,
                notes=notes,
            )
        first_failure = first_failure or failure
        notes.append(f"{c.label}: {failure[1]}")

    code, msg = first_failure
    primary = candidates[0]
    details = {"contract": primary.label, "notes": notes}
    if len(candidates) > 1:
        raise SelectionError("NO_ACCEPTABLE_CONTRACT", f"No candidate passed filters; primary {primary.label}: {msg}", details)
    raise SelectionError(code, f"{primary.label}: {msg}", details)
