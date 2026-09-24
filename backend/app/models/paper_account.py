from datetime import datetime

from sqlalchemy import Boolean, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UTCDateTime


class PaperAccount(Base):
    __tablename__ = "paper_accounts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(64))
    starting_balance: Mapped[float] = mapped_column(Float)
    peak_equity: Mapped[float] = mapped_column(Float)
    max_drawdown: Mapped[float] = mapped_column(Float, default=0.0)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    closed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)


class LedgerType:
    DEPOSIT = "DEPOSIT"
    OPTION_PURCHASE = "OPTION_PURCHASE"
    OPTION_SALE = "OPTION_SALE"
    FEE = "FEE"


class PaperLedgerEntry(Base):
    """Cash is never stored as a mutable number: it is the sum of this ledger."""

    __tablename__ = "paper_ledger"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("paper_accounts.id"), index=True)
    at: Mapped[datetime] = mapped_column(UTCDateTime)
    entry_type: Mapped[str] = mapped_column(String(24))
    amount: Mapped[float] = mapped_column(Float)  # signed: + adds cash, - removes cash
    trade_id: Mapped[int | None] = mapped_column(ForeignKey("trades.id"), index=True)
    description: Mapped[str | None] = mapped_column(Text)


class EquitySnapshot(Base):
    __tablename__ = "equity_snapshots"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("paper_accounts.id"), index=True)
    at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)
    cash: Mapped[float] = mapped_column(Float)
    market_value: Mapped[float] = mapped_column(Float)
    equity: Mapped[float] = mapped_column(Float)
    realized_pnl: Mapped[float] = mapped_column(Float)
    unrealized_pnl: Mapped[float] = mapped_column(Float)
    drawdown: Mapped[float] = mapped_column(Float)
