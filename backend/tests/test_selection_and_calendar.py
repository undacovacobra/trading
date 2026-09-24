import asyncio
from datetime import date, datetime

import pytest

from app.clock import NY, FixedClock
from app.market_data.simulated import SimulatedMarketData, occ_symbol
from app.schemas.config import RiskConfig, StrategyConfig
from app.services import market_calendar as cal
from app.services.option_selector import SelectionError, choose_expiration, select_contract
from app.services.position_sizer import size_position
from app.services.symbols import futures_root

NOW = datetime(2026, 9, 24, 10, 0, tzinfo=NY)  # Thursday


def _select(cfg, direction="LONG", market=None, now=NOW):
    market = market or SimulatedMarketData(FixedClock(now))
    return asyncio.run(select_contract(market, underlying="QQQ", direction=direction, cfg=cfg, risk=RiskConfig(), now=now))


@pytest.mark.parametrize(
    "sym,root", [("NQ1!", "NQ"), ("CME_MINI:NQ1!", "NQ"), ("NQZ2026", "NQ"), ("MNQU6", "MNQ"), ("ES", "ES"), ("RTY1!", "RTY")]
)
def test_futures_root(sym, root):
    assert futures_root(sym) == root


def test_calendar_holidays_and_early_closes():
    assert not cal.is_trading_day(date(2026, 11, 26))  # Thanksgiving
    assert cal.session_close(date(2026, 11, 27)).hour == 13  # early close
    assert not cal.is_trading_day(date(2026, 4, 3))  # Good Friday 2026
    assert not cal.is_trading_day(date(2026, 7, 3))  # July 4 on Saturday -> observed Friday
    assert not cal.is_trading_day(date(2026, 6, 19))  # Juneteenth
    assert cal.session_close(date(2026, 12, 24)).hour == 13
    assert cal.next_trading_day(date(2026, 9, 25)) == date(2026, 9, 28)  # Fri -> Mon
    assert cal.trading_days_until(date(2026, 9, 25), date(2026, 9, 28)) == 1


def test_dst_bounds_are_zone_aware():
    s1, e1 = cal.ny_day_bounds_utc(date(2026, 3, 8))  # DST starts: 23-hour day
    assert (e1 - s1).total_seconds() == 23 * 3600
    s2, _ = cal.ny_day_bounds_utc(date(2026, 7, 1))
    assert s2.astimezone(NY).hour == 0


def test_expiration_modes():
    exps = [date(2026, 9, 24), date(2026, 9, 25), date(2026, 9, 28), date(2026, 10, 2)]
    today = date(2026, 9, 24)
    assert choose_expiration(exps, StrategyConfig(expiration_mode="SAME_DAY"), today) == today
    assert choose_expiration(exps, StrategyConfig(expiration_mode="EXACT_DTE", dte=1), today) == date(2026, 9, 25)
    assert choose_expiration(exps, StrategyConfig(expiration_mode="MIN_DTE", dte=3), today) == date(2026, 10, 2)
    assert choose_expiration(exps[1:], StrategyConfig(expiration_mode="NEXT_AVAILABLE"), today) == date(2026, 9, 25)
    with pytest.raises(SelectionError) as e:
        choose_expiration(exps[1:], StrategyConfig(expiration_mode="SAME_DAY"), today)
    assert e.value.code == "NO_EXPIRATION"
    # Friday 1DTE -> Monday
    assert choose_expiration([date(2026, 9, 28)], StrategyConfig(expiration_mode="EXACT_DTE", dte=1), date(2026, 9, 25)) == date(
        2026, 9, 28
    )


def test_atm_call_and_put():
    assert _select(StrategyConfig()).contract.strike == 604
    sel = _select(StrategyConfig(), "SHORT")
    assert sel.option_type == "PUT" and sel.contract.strike == 604


def test_otm_strikes_direction():
    assert _select(StrategyConfig(strike_mode="OTM_STRIKES", otm_strikes=1)).contract.strike == 605
    assert _select(StrategyConfig(strike_mode="OTM_STRIKES", otm_strikes=1), "SHORT").contract.strike == 603
    assert _select(StrategyConfig(strike_mode="OTM_STRIKES", otm_strikes=-1)).contract.strike == 603  # 1 ITM call


def test_otm_percent_and_fixed_offset():
    assert _select(StrategyConfig(strike_mode="OTM_PERCENT", otm_percent=0.5)).contract.strike == 607  # 603.72*1.005=606.74
    assert _select(StrategyConfig(strike_mode="FIXED_OFFSET", strike_offset=2), "SHORT").contract.strike == 602


def test_delta_mode():
    sel = _select(StrategyConfig(strike_mode="DELTA", target_delta=0.30))
    assert abs(abs(sel.quote.delta) - 0.30) < 0.08
    assert sel.contract.strike > 604


def test_premium_mode():
    sel = _select(StrategyConfig(strike_mode="PREMIUM", target_premium=1.00, filters={"min_bid": 0.05}))
    assert abs(sel.quote.ask - 1.00) < 0.35


def test_no_silent_fallback_but_explicit_fallback_allowed():
    market = SimulatedMarketData(FixedClock(NOW))
    market.set_quote_override(occ_symbol("QQQ", date(2026, 9, 24), "CALL", 604), bid=1.0, ask=2.0)
    with pytest.raises(SelectionError) as e:
        _select(StrategyConfig(), market=market)
    assert e.value.code == "SPREAD_TOO_WIDE"
    sel = _select(StrategyConfig(fallback_policy="NEAREST_PASSING", fallback_max_steps=1), market=market)
    assert sel.fallback_used and sel.contract.strike == 603 and sel.notes


def test_sizing_modes():
    risk = RiskConfig(max_trade_cost=5000, max_contracts_per_trade=100)
    assert size_position(StrategyConfig(position_size_dollars=1000, max_contracts=100), risk, 2.40, 25_000).quantity == 4
    assert size_position(StrategyConfig(sizing_mode="FIXED_CONTRACTS", fixed_contracts=5), risk, 2.40, 0).quantity == 5
    r = size_position(StrategyConfig(sizing_mode="PERCENT_EQUITY", percent_equity=5, max_contracts=100), risk, 2.40, 25_000)
    assert r.quantity == 5 and r.estimated_cost == 1200  # $1,250 budget
    capped = size_position(StrategyConfig(sizing_mode="FIXED_CONTRACTS", fixed_contracts=20), RiskConfig(), 2.40, 0)
    assert capped.quantity == 4 and capped.notes  # $1,000 global max trade cost
