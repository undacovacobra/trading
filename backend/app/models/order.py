from datetime import datetime

from sqlalchemy import JSON, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base, UTCDateTime


class Order(Base):
    """An entry or exit order, simulated (OBSERVE) or real (LIVE)."""

    __tablename__ = "orders"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    trade_id: Mapped[int] = mapped_column(ForeignKey("trades.id"), index=True)
    client_order_id: Mapped[str] = mapped_column(String(64), unique=True)
    mode: Mapped[str] = mapped_column(String(16))
    side: Mapped[str] = mapped_column(String(16))  # BUY_TO_OPEN / SELL_TO_CLOSE
    order_type: Mapped[str] = mapped_column(String(16))  # SIMULATED / LIMIT
    contract_id: Mapped[str] = mapped_column(String(128))
    requested_quantity: Mapped[int] = mapped_column(Integer)
    limit_price: Mapped[float | None] = mapped_column(Float)
    status: Mapped[str] = mapped_column(String(24))
    filled_quantity: Mapped[int] = mapped_column(Integer, default=0)
    avg_fill_price: Mapped[float | None] = mapped_column(Float)
    bid_at_request: Mapped[float | None] = mapped_column(Float)
    ask_at_request: Mapped[float | None] = mapped_column(Float)
    mid_at_request: Mapped[float | None] = mapped_column(Float)
    quote_time: Mapped[datetime | None] = mapped_column(UTCDateTime)
    slippage_assumption: Mapped[float | None] = mapped_column(Float)
    fees: Mapped[float] = mapped_column(Float, default=0.0)
    broker_order_id: Mapped[str | None] = mapped_column(String(128), index=True)
    broker_account_ref: Mapped[str | None] = mapped_column(String(128))
    cancel_status: Mapped[str | None] = mapped_column(String(24))
    error: Mapped[str | None] = mapped_column(Text)
    raw_response: Mapped[dict | None] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    submitted_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    filled_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    trade: Mapped["Trade"] = relationship(back_populates="orders")  # noqa: F821
    fills: Mapped[list["Fill"]] = relationship(back_populates="order", order_by="Fill.id")


class Fill(Base):
    __tablename__ = "fills"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    order_id: Mapped[int] = mapped_column(ForeignKey("orders.id"), index=True)
    quantity: Mapped[int] = mapped_column(Integer)
    price: Mapped[float] = mapped_column(Float)
    at: Mapped[datetime] = mapped_column(UTCDateTime)
    simulated: Mapped[bool] = mapped_column(default=False)
    broker_fill_id: Mapped[str | None] = mapped_column(String(128))

    order: Mapped[Order] = relationship(back_populates="fills")


class OrderStatus:
    SIMULATED_FILLED = "SIMULATED_FILLED"
    PENDING_REVIEW = "PENDING_REVIEW"
    SUBMITTED = "SUBMITTED"
    PARTIALLY_FILLED = "PARTIALLY_FILLED"
    FILLED = "FILLED"
    CANCELLED = "CANCELLED"
    REJECTED = "REJECTED"
    ERROR = "ERROR"
