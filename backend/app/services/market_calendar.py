"""US equity-options market calendar (NYSE holidays and early closes).

Session times are expressed in America/New_York wall-clock time and converted
with zoneinfo, so daylight-saving transitions are handled correctly. Never use
fixed UTC offsets for session logic.
"""

from datetime import date, datetime, time, timedelta
from functools import lru_cache

from app.clock import NY, UTC

REGULAR_OPEN = time(9, 30)
REGULAR_CLOSE = time(16, 0)
EARLY_CLOSE = time(13, 0)


def _easter(year: int) -> date:
    """Gregorian Easter Sunday (anonymous Gregorian algorithm)."""
    a = year % 19
    b, c = divmod(year, 100)
    d, e = divmod(b, 4)
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = divmod(c, 4)
    l = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l) // 451
    month = (h + l - 7 * m + 114) // 31
    day = (h + l - 7 * m + 114) % 31 + 1
    return date(year, month, day)


def _nth_weekday(year: int, month: int, weekday: int, n: int) -> date:
    d = date(year, month, 1)
    d += timedelta(days=(weekday - d.weekday()) % 7)
    return d + timedelta(weeks=n - 1)


def _last_weekday(year: int, month: int, weekday: int) -> date:
    d = date(year + (month == 12), month % 12 + 1, 1) - timedelta(days=1)
    return d - timedelta(days=(d.weekday() - weekday) % 7)


def _observed(d: date) -> date:
    if d.weekday() == 5:  # Saturday -> Friday
        return d - timedelta(days=1)
    if d.weekday() == 6:  # Sunday -> Monday
        return d + timedelta(days=1)
    return d


@lru_cache(maxsize=64)
def holidays(year: int) -> frozenset[date]:
    h: set[date] = set()
    new_year = date(year, 1, 1)
    if new_year.weekday() == 6:
        h.add(new_year + timedelta(days=1))
    elif new_year.weekday() < 5:
        h.add(new_year)
    # (NYSE does not observe New Year's Day on the prior Friday when it falls on a Saturday.)
    h.add(_nth_weekday(year, 1, 0, 3))  # MLK Day
    h.add(_nth_weekday(year, 2, 0, 3))  # Presidents' Day
    h.add(_easter(year) - timedelta(days=2))  # Good Friday
    h.add(_last_weekday(year, 5, 0))  # Memorial Day
    if year >= 2022:
        h.add(_observed(date(year, 6, 19)))  # Juneteenth
    h.add(_observed(date(year, 7, 4)))  # Independence Day
    h.add(_nth_weekday(year, 9, 0, 1))  # Labor Day
    h.add(_nth_weekday(year, 11, 3, 4))  # Thanksgiving
    h.add(_observed(date(year, 12, 25)))  # Christmas
    return frozenset(h)


@lru_cache(maxsize=64)
def early_closes(year: int) -> frozenset[date]:
    e: set[date] = set()
    july3 = date(year, 7, 3)
    if july3.weekday() < 4:  # Mon-Thu, and July 4 is the next weekday
        e.add(july3)
    e.add(_nth_weekday(year, 11, 3, 4) + timedelta(days=1))  # day after Thanksgiving
    xmas_eve = date(year, 12, 24)
    if xmas_eve.weekday() < 4:
        e.add(xmas_eve)
    return frozenset(e - holidays(year))


def is_trading_day(d: date) -> bool:
    return d.weekday() < 5 and d not in holidays(d.year)


def session_close(d: date) -> time | None:
    if not is_trading_day(d):
        return None
    return EARLY_CLOSE if d in early_closes(d.year) else REGULAR_CLOSE


def close_shift(d: date) -> timedelta:
    """How much earlier than a regular 16:00 close the session ends (0 on normal days)."""
    close = session_close(d) or REGULAR_CLOSE
    return datetime.combine(d, REGULAR_CLOSE) - datetime.combine(d, close)


def next_trading_day(d: date) -> date:
    d += timedelta(days=1)
    while not is_trading_day(d):
        d += timedelta(days=1)
    return d


def trading_days_until(start: date, end: date) -> int:
    """Number of trading sessions after `start` up to and including `end`.

    trading_days_until(today, today) == 0  (0DTE)
    trading_days_until(Friday, next Monday) == 1  (1DTE)
    """
    if end <= start:
        return 0
    n, d = 0, start
    while d < end:
        d = next_trading_day(d)
        if d <= end:
            n += 1
    return n


def nth_trading_day(start: date, n: int) -> date:
    d = start
    for _ in range(n):
        d = next_trading_day(d)
    return d


def ny_date(dt: datetime) -> date:
    return dt.astimezone(NY).date()


def ny_day_bounds_utc(d: date) -> tuple[datetime, datetime]:
    """UTC [start, end) covering the New York calendar day `d` (DST-aware)."""
    start = datetime.combine(d, time(0), tzinfo=NY).astimezone(UTC)
    end = datetime.combine(d + timedelta(days=1), time(0), tzinfo=NY).astimezone(UTC)
    return start, end
