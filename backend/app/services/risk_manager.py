"""Hard safety rules for NEW ENTRIES. Exits are never blocked by anything here."""

from datetime import datetime, time

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.clock import NY
from app.models import Trade
from app.schemas.config import RiskConfig, SystemSettings
from app.services.market_calendar import close_shift, is_trading_day, ny_date, ny_day_bounds_utc
from app.services.state_machine import HOLDING_STATES, TradeState


class RiskRejection(RuntimeError):
    def __init__(self, code: str, message: str, *, is_limit: bool = True):
        super().__init__(message)
        self.code = code
        self.message = message
        self.is_limit = is_limit


def _shifted(d, hhmm: str) -> time:
    t = datetime.combine(d, time.fromisoformat(hhmm)) - close_shift(d)
    return t.time()


def check_pre_entry(session: Session, *, system: SystemSettings, risk: RiskConfig, mode: str, now: datetime) -> None:
    if system.paused:
        raise RiskRejection("PAUSED", "New entries are paused (kill switch)", is_limit=False)
    if system.out_of_sync:
        raise RiskRejection("SYSTEM_OUT_OF_SYNC", f"New entries disabled: {system.out_of_sync_reason}", is_limit=False)

    ny = now.astimezone(NY)
    d = ny.date()
    if not is_trading_day(d):
        raise RiskRejection("MARKET_CLOSED", f"{d} is not a trading day", is_limit=False)
    start, end = time.fromisoformat(risk.trading_start), _shifted(d, risk.trading_end)
    if not (start <= ny.time() < end):
        raise RiskRejection(
            "OUTSIDE_TRADING_HOURS", f"{ny:%H:%M:%S} ET outside allowed entry window {start:%H:%M}-{end:%H:%M}", is_limit=False
        )

    day_start, day_end = ny_day_bounds_utc(d)
    trades_today = session.scalar(
        select(func.count(Trade.id)).where(
            Trade.mode == mode,
            Trade.created_at >= day_start,
            Trade.created_at < day_end,
            Trade.entry_fill.is_not(None),
        )
    )
    if trades_today >= risk.max_daily_trades:
        raise RiskRejection("MAX_DAILY_TRADES", f"{trades_today} trades today >= limit {risk.max_daily_trades}")

    closed_today = session.scalars(
        select(Trade)
        .where(Trade.mode == mode, Trade.state == TradeState.CLOSED, Trade.closed_at >= day_start, Trade.closed_at < day_end)
        .order_by(Trade.closed_at.desc(), Trade.id.desc())
    ).all()
    realized_today = sum(t.realized_pnl or 0 for t in closed_today)
    if realized_today <= -risk.max_daily_loss:
        raise RiskRejection("MAX_DAILY_LOSS", f"realized P&L today {realized_today:.2f} <= -{risk.max_daily_loss:.2f}")

    streak = 0
    for t in closed_today:
        if (t.realized_pnl or 0) < 0:
            streak += 1
        else:
            break
    if streak >= risk.max_consecutive_losses:
        raise RiskRejection("MAX_CONSECUTIVE_LOSSES", f"{streak} consecutive losses today >= {risk.max_consecutive_losses}")

    open_count = session.scalar(select(func.count(Trade.id)).where(Trade.mode == mode, Trade.state.in_(HOLDING_STATES)))
    if open_count >= risk.max_open_positions:
        raise RiskRejection("MAX_OPEN_POSITIONS", f"{open_count} open positions >= limit {risk.max_open_positions}")


def check_post_selection(
    session: Session,
    *,
    risk: RiskConfig,
    mode: str,
    now: datetime,
    expiration,
    quantity: int,
    estimated_cost: float,
    estimated_fees: float,
    buying_power: float,
    buying_power_code: str,
) -> None:
    d = ny_date(now)
    if expiration == d:
        latest = _shifted(d, risk.latest_0dte_entry)
        if now.astimezone(NY).time() >= latest:
            raise RiskRejection("LATEST_0DTE_ENTRY_PASSED", f"0DTE entries not allowed after {latest:%H:%M} ET", is_limit=False)
    if quantity <= 0:
        raise RiskRejection("QUANTITY_ZERO", "Position size rounds to 0 contracts at the current price", is_limit=False)
    # Hard backstops (the sizer already caps; these guarantee it before any order).
    if estimated_cost > risk.max_trade_cost + 1e-6:
        raise RiskRejection("MAX_TRADE_COST", f"cost {estimated_cost:.2f} > max {risk.max_trade_cost:.2f}")
    if quantity > risk.max_contracts_per_trade:
        raise RiskRejection("MAX_CONTRACTS", f"{quantity} contracts > max {risk.max_contracts_per_trade}")
    exposure = session.scalar(
        select(func.coalesce(func.sum(Trade.gross_cost), 0.0)).where(Trade.mode == mode, Trade.state.in_(HOLDING_STATES))
    )
    if exposure + estimated_cost > risk.max_total_exposure + 1e-6:
        raise RiskRejection(
            "MAX_TOTAL_EXPOSURE", f"exposure {exposure:.2f} + {estimated_cost:.2f} > max {risk.max_total_exposure:.2f}"
        )
    if estimated_cost + estimated_fees > buying_power + 1e-6:
        raise RiskRejection(
            buying_power_code,
            f"needs {estimated_cost + estimated_fees:.2f}, available {buying_power:.2f}",
            is_limit=False,
        )

