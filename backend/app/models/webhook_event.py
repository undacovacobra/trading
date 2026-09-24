from datetime import datetime

from sqlalchemy import JSON, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UTCDateTime


class EventStatus:
    RECEIVED = "RECEIVED"  # stored, waiting for the worker
    PROCESSING = "PROCESSING"
    PROCESSED = "PROCESSED"  # an action was taken (opened / closed / dry-run shown)
    IGNORED = "IGNORED"  # valid but nothing to do (rejected by risk, no position, ...)
    FAILED = "FAILED"  # something went wrong; see result_code
    INTERRUPTED = "INTERRUPTED"  # process died mid-way with broker side effects possible


class WebhookEvent(Base):
    __tablename__ = "webhook_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    # Unique constraint = idempotency. A repeated webhook can never create a second trade.
    event_id: Mapped[str] = mapped_column(String(128), unique=True, nullable=False)
    source: Mapped[str] = mapped_column(String(32), default="tradingview")
    strategy_id: Mapped[str] = mapped_column(String(64), index=True)
    symbol: Mapped[str] = mapped_column(String(64))
    action: Mapped[str] = mapped_column(String(32))
    tv_trade_id: Mapped[str | None] = mapped_column(String(128))
    signal_time: Mapped[datetime | None] = mapped_column(UTCDateTime)
    received_at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)
    processing_started_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    processed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    status: Mapped[str] = mapped_column(String(16), default=EventStatus.RECEIVED, index=True)
    result_code: Mapped[str | None] = mapped_column(String(64))
    result_message: Mapped[str | None] = mapped_column(Text)
    duplicate_count: Mapped[int] = mapped_column(Integer, default=0)
    source_ip: Mapped[str | None] = mapped_column(String(64))
    # Payload with the secret removed.
    payload: Mapped[dict] = mapped_column(JSON)
