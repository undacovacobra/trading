"""Time source for the application.

All timestamps are timezone-aware UTC internally. America/New_York is only used
for session logic and display. A Clock object is injected everywhere so tests
(and replays) can control "now".
"""

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

NY = ZoneInfo("America/New_York")
UTC = timezone.utc


class Clock:
    def now(self) -> datetime:
        return datetime.now(UTC)


class FixedClock(Clock):
    """Manually controlled clock for tests."""

    def __init__(self, at: datetime):
        if at.tzinfo is None:
            raise ValueError("FixedClock requires a timezone-aware datetime")
        self._at = at.astimezone(UTC)

    def now(self) -> datetime:
        return self._at

    def set(self, at: datetime) -> None:
        self._at = at.astimezone(UTC)

    def advance(self, **kwargs) -> None:
        self._at += timedelta(**kwargs)


def to_ny(dt: datetime) -> datetime:
    return dt.astimezone(NY)
