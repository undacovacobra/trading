"""Internal virtual brokerage account for OBSERVE mode.

Cash is reconstructed from the ledger; it is never an independently mutated
number. Open positions are marked at the BID (conservative for long options).
"""

from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import EquitySnapshot, LedgerType, PaperAccount, PaperLedgerEntry, Trade
from app.services.position_sizer import CONTRACT_MULTIPLIER
from app.services.state_machine import HOLDING_STATES, TradeState

EPS = 0.005


def get_active_account(session: Session, *, starting_balance: float, now: datetime) -> PaperAccount:
    acct = session.scalar(select(PaperAccount).where(PaperAccount.is_active.is_(True)).order_by(PaperAccount.id.desc()))
    if acct is None:
        acct = open_account(session, starting_balance=starting_balance, now=now)
    return acct


def open_account(session: Session, *, starting_balance: float, now: datetime, name: str = "Paper") -> PaperAccount:
    acct = PaperAccount(
        name=name, starting_balance=round(starting_balance, 2), peak_equity=round(starting_balance, 2), created_at=now
    )
    session.add(acct)
    session.flush()
    add_ledger(session, acct, LedgerType.DEPOSIT, starting_balance, now, description="Starting balance")
    session.add(
        EquitySnapshot(
            account_id=acct.id,
            at=now,
            cash=acct.starting_balance,
            market_value=0.0,
            equity=acct.starting_balance,
            realized_pnl=0.0,
            unrealized_pnl=0.0,
            drawdown=0.0,
        )
    )
    return acct


def reset_account(session: Session, *, starting_balance: float, now: datetime) -> PaperAccount:
    current = session.scalar(select(PaperAccount).where(PaperAccount.is_active.is_(True)))
    if current is not None:
        if open_trades(session, current.id):
            raise ValueError("Close all paper positions before resetting the paper account")
        current.is_active = False
        current.closed_at = now
    return open_account(session, starting_balance=starting_balance, now=now)


def add_ledger(
    session: Session,
    acct: PaperAccount,
    entry_type: str,
    amount: float,
    at: datetime,
    *,
    trade_id: int | None = None,
    description: str | None = None,
) -> None:
    session.add(
        PaperLedgerEntry(
            account_id=acct.id, at=at, entry_type=entry_type, amount=round(amount, 2), trade_id=trade_id, description=description
        )
    )
    session.flush()


def cash(session: Session, account_id: int) -> float:
    return round(
        session.scalar(select(func.coalesce(func.sum(PaperLedgerEntry.amount), 0.0)).where(PaperLedgerEntry.account_id == account_id)),
        2,
    )


def open_trades(session: Session, account_id: int) -> list[Trade]:
    return list(
        session.scalars(
            select(Trade).where(Trade.paper_account_id == account_id, Trade.state.in_(HOLDING_STATES)).order_by(Trade.id)
        )
    )


def mark_price(t: Trade) -> float:
    """Conservative liquidation value per share: current bid, else bid at entry."""
    if t.current_bid is not None:
        return t.current_bid
    return t.entry_bid or 0.0


def summary(session: Session, acct: PaperAccount) -> dict:
    c = cash(session, acct.id)
    opens = open_trades(session, acct.id)
    market_value = round(sum(mark_price(t) * CONTRACT_MULTIPLIER * (t.quantity or 0) for t in opens), 2)
    cost_basis = round(sum((t.gross_cost or 0) for t in opens), 2)
    realized = round(
        session.scalar(
            select(func.coalesce(func.sum(Trade.realized_pnl), 0.0)).where(
                Trade.paper_account_id == acct.id, Trade.state == TradeState.CLOSED
            )
        ),
        2,
    )
    open_fees = round(sum((t.fees or 0) for t in opens), 2)
    unrealized = round(market_value - cost_basis, 2)
    equity = round(c + market_value, 2)
    peak = max(acct.peak_equity, equity)
    drawdown = round(peak - equity, 2)
    return {
        "account_id": acct.id,
        "starting_balance": acct.starting_balance,
        "cash": c,
        "available_cash": c,
        "reserved_cash": 0.0,
        "market_value": market_value,
        "cost_basis": cost_basis,
        "open_fees": open_fees,
        "unrealized_pnl": unrealized,
        "realized_pnl": realized,
        "equity": equity,
        "peak_equity": round(peak, 2),
        "drawdown": drawdown,
        "drawdown_pct": round(drawdown / peak * 100, 2) if peak else 0.0,
        "max_drawdown": round(max(acct.max_drawdown, drawdown), 2),
        "total_return_pct": round((equity - acct.starting_balance) / acct.starting_balance * 100, 2),
        "open_positions": len(opens),
        "created_at": acct.created_at.isoformat(),
    }


def snapshot(session: Session, acct: PaperAccount, now: datetime) -> dict:
    s = summary(session, acct)
    acct.peak_equity = s["peak_equity"]
    acct.max_drawdown = s["max_drawdown"]
    session.add(
        EquitySnapshot(
            account_id=acct.id,
            at=now,
            cash=s["cash"],
            market_value=s["market_value"],
            equity=s["equity"],
            realized_pnl=s["realized_pnl"],
            unrealized_pnl=s["unrealized_pnl"],
            drawdown=s["drawdown"],
        )
    )
    return s


def check_consistency(session: Session, acct: PaperAccount) -> list[str]:
    """Section 53: trades, ledger, cash and equity must agree mathematically."""
    problems: list[str] = []
    c = cash(session, acct.id)
    if c < -EPS:
        problems.append(f"negative paper cash {c:.2f}")

    trades = session.scalars(select(Trade).where(Trade.paper_account_id == acct.id, Trade.entry_fill.is_not(None))).all()
    ledger = session.scalars(select(PaperLedgerEntry).where(PaperLedgerEntry.account_id == acct.id)).all()
    by_trade: dict[int, list[PaperLedgerEntry]] = {}
    deposits = 0.0
    for e in ledger:
        if e.trade_id is None:
            deposits += e.amount
        else:
            by_trade.setdefault(e.trade_id, []).append(e)

    expected = deposits
    for t in trades:
        entries = by_trade.pop(t.id, [])
        purchases = [e for e in entries if e.entry_type == LedgerType.OPTION_PURCHASE]
        sales = [e for e in entries if e.entry_type == LedgerType.OPTION_SALE]
        if len(purchases) != 1:
            problems.append(f"trade {t.trade_uid}: {len(purchases)} purchase ledger entries (expected 1)")
        if t.state == TradeState.CLOSED:
            if len(sales) != 1:
                problems.append(f"trade {t.trade_uid}: closed with {len(sales)} sale ledger entries (expected 1)")
            expected += t.realized_pnl or 0
            net = sum(e.amount for e in entries)
            if abs(net - (t.realized_pnl or 0)) > EPS:
                problems.append(f"trade {t.trade_uid}: ledger net {net:.2f} != realized P&L {t.realized_pnl:.2f}")
        elif t.state in HOLDING_STATES:
            if sales:
                problems.append(f"trade {t.trade_uid}: open but has a sale ledger entry")
            expected -= (t.gross_cost or 0) + (t.fees or 0)
    for trade_id in by_trade:
        problems.append(f"ledger entries reference trade {trade_id} with no recorded fill")
    if abs(expected - c) > EPS:
        problems.append(f"cash {c:.2f} != deposits + realized - open cost ({expected:.2f})")
    return problems
