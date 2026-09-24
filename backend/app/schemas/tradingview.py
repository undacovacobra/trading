from datetime import datetime, timezone
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


class Action(str, Enum):
    ENTER_LONG = "ENTER_LONG"
    ENTER_SHORT = "ENTER_SHORT"
    EXIT_LONG = "EXIT_LONG"
    EXIT_SHORT = "EXIT_SHORT"
    CLOSE_ALL = "CLOSE_ALL"
    REVERSE_LONG = "REVERSE_LONG"
    REVERSE_SHORT = "REVERSE_SHORT"


SUPPORTED_ACTIONS = {a.value for a in Action}
ENTRY_ACTIONS = {Action.ENTER_LONG: "LONG", Action.ENTER_SHORT: "SHORT"}
EXIT_ACTIONS = {Action.EXIT_LONG: "LONG", Action.EXIT_SHORT: "SHORT"}
REVERSE_ACTIONS = {Action.REVERSE_LONG: "LONG", Action.REVERSE_SHORT: "SHORT"}


class TradingViewSignal(BaseModel):
    """Standard webhook schema (section 8).

    Numbers may arrive as strings from Pine placeholders; pydantic coerces them.
    Unknown extra fields are kept so nothing TradingView sends is lost.
    """

    model_config = ConfigDict(extra="allow", str_strip_whitespace=True)

    schema_version: int = 1
    secret: str = Field(repr=False)
    event_id: str = Field(min_length=1, max_length=128)
    strategy_id: str = Field(min_length=1, max_length=64)
    symbol: str = Field(min_length=1, max_length=64)
    action: Action
    signal_time: datetime | None = None
    trade_id: str | None = Field(default=None, max_length=128)
    entry_price: float | None = None
    stop_price: float | None = None
    target_price: float | None = None
    risk_points: float | None = None
    futures_price: float | None = None
    reason: str | None = Field(default=None, max_length=64)
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("action", mode="before")
    @classmethod
    def _upper_action(cls, v):
        return v.strip().upper() if isinstance(v, str) else v

    @field_validator("signal_time")
    @classmethod
    def _aware(cls, v: datetime | None):
        if v is not None and v.tzinfo is None:
            # TradingView {{timenow}} is UTC; treat naive timestamps as UTC.
            v = v.replace(tzinfo=timezone.utc)
        return v

    @field_validator("trade_id", "reason", mode="before")
    @classmethod
    def _blank_to_none(cls, v):
        if isinstance(v, str) and not v.strip():
            return None
        return v

    def public_payload(self) -> dict:
        """Payload safe to persist/log: the secret is removed."""
        return self.model_dump(mode="json", exclude={"secret"})
