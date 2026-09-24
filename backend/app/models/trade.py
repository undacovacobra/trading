from datetime import date, datetime

from sqlalchemy import JSON, Date, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base, UTCDateTime


class Trade(Base):
    """One strategy trade: a futures signal expressed through one option position.

    This row is the ownership record (section 29): strategy trade -> specific
    contract -> specific quantity -> specific orders. The application only ever
    closes contracts it can find here, never "all QQQ options".
    """

    __tablename__ = "trades"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    trade_uid: Mapped[str] = mapped_column(String(32), unique=True)
    strategy_id: Mapped[str] = mapped_column(String(64), index=True)
    mode: Mapped[str] = mapped_column(String(16), index=True)  # OBSERVE / DRY_RUN / LIVE
    state: Mapped[str] = mapped_column(String(32), index=True)
    paper_account_id: Mapped[int | None] = mapped_column(ForeignKey("paper_accounts.id"))

    source_event_id: Mapped[str] = mapped_column(String(128), index=True)
    exit_event_id: Mapped[str | None] = mapped_column(String(128), index=True)
    tv_trade_id: Mapped[str | None] = mapped_column(String(128), index=True)

    # Futures signal
    futures_symbol: Mapped[str] = mapped_column(String(64))
    futures_root: Mapped[str] = mapped_column(String(16))
    futures_direction: Mapped[str] = mapped_column(String(8))  # LONG / SHORT
    futures_entry: Mapped[float | None] = mapped_column(Float)
    futures_stop: Mapped[float | None] = mapped_column(Float)
    futures_target: Mapped[float | None] = mapped_column(Float)
    futures_exit: Mapped[float | None] = mapped_column(Float)
    risk_points: Mapped[float | None] = mapped_column(Float)
    signal_type: Mapped[str | None] = mapped_column(String(64))

    # Option contract
    option_underlying: Mapped[str | None] = mapped_column(String(16))
    underlying_price_at_signal: Mapped[float | None] = mapped_column(Float)
    contract_id: Mapped[str | None] = mapped_column(String(128))
    option_symbol: Mapped[str | None] = mapped_column(String(64))
    option_type: Mapped[str | None] = mapped_column(String(4))  # CALL / PUT
    strike: Mapped[float | None] = mapped_column(Float)
    expiration: Mapped[date | None] = mapped_column(Date)
    expiration_mode: Mapped[str | None] = mapped_column(String(32))
    strike_mode: Mapped[str | None] = mapped_column(String(32))
    sizing_mode: Mapped[str | None] = mapped_column(String(32))
    quantity: Mapped[int | None] = mapped_column(Integer)

    # Entry (quote captured at the moment of the signal, never reconstructed later)
    entry_bid: Mapped[float | None] = mapped_column(Float)
    entry_ask: Mapped[float | None] = mapped_column(Float)
    entry_mid: Mapped[float | None] = mapped_column(Float)
    entry_quote_time: Mapped[datetime | None] = mapped_column(UTCDateTime)
    entry_slippage: Mapped[float | None] = mapped_column(Float)
    entry_fill: Mapped[float | None] = mapped_column(Float)
    entry_timestamp: Mapped[datetime | None] = mapped_column(UTCDateTime)

    # Exit
    exit_bid: Mapped[float | None] = mapped_column(Float)
    exit_ask: Mapped[float | None] = mapped_column(Float)
    exit_mid: Mapped[float | None] = mapped_column(Float)
    exit_quote_time: Mapped[datetime | None] = mapped_column(UTCDateTime)
    exit_slippage: Mapped[float | None] = mapped_column(Float)
    exit_fill: Mapped[float | None] = mapped_column(Float)
    exit_timestamp: Mapped[datetime | None] = mapped_column(UTCDateTime)
    exit_reason: Mapped[str | None] = mapped_column(String(64))

    # Money
    gross_cost: Mapped[float | None] = mapped_column(Float)
    gross_proceeds: Mapped[float | None] = mapped_column(Float)
    fees: Mapped[float] = mapped_column(Float, default=0.0)
    realized_pnl: Mapped[float | None] = mapped_column(Float)
    return_percent: Mapped[float | None] = mapped_column(Float)

    # Mark-to-market (long options are marked at the BID)
    current_bid: Mapped[float | None] = mapped_column(Float)
    current_ask: Mapped[float | None] = mapped_column(Float)
    current_quote_time: Mapped[datetime | None] = mapped_column(UTCDateTime)

    # Timing metrics
    signal_time: Mapped[datetime | None] = mapped_column(UTCDateTime)
    webhook_received_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    contract_selected_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    execution_requested_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    exit_signal_time: Mapped[datetime | None] = mapped_column(UTCDateTime)
    exit_received_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    # Live
    broker_account_ref: Mapped[str | None] = mapped_column(String(128))

    reject_code: Mapped[str | None] = mapped_column(String(64))
    reject_message: Mapped[str | None] = mapped_column(Text)
    intent: Mapped[dict | None] = mapped_column(JSON)
    selection_notes: Mapped[list | None] = mapped_column(JSON)

    created_at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime)
    closed_at: Mapped[datetime | None] = mapped_column(UTCDateTime, index=True)

    transitions: Mapped[list["TradeStateTransition"]] = relationship(
        back_populates="trade", order_by="TradeStateTransition.id"
    )
    orders: Mapped[list["Order"]] = relationship(back_populates="trade", order_by="Order.id")  # noqa: F821


class TradeStateTransition(Base):
    __tablename__ = "trade_state_transitions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    trade_id: Mapped[int] = mapped_column(ForeignKey("trades.id"), index=True)
    from_state: Mapped[str | None] = mapped_column(String(32))
    to_state: Mapped[str] = mapped_column(String(32))
    at: Mapped[datetime] = mapped_column(UTCDateTime)
    note: Mapped[str | None] = mapped_column(Text)

    trade: Mapped[Trade] = relationship(back_populates="transitions")
