"""Performance statistics over closed trades."""

from collections import defaultdict
from collections.abc import Callable, Iterable

from app.clock import NY
from app.models import Trade

GROUPERS: dict[str, Callable[[Trade], str]] = {
    "strategy": lambda t: t.strategy_id,
    "direction": lambda t: t.futures_direction,
    "signal_type": lambda t: t.signal_type or "unspecified",
    "date": lambda t: (t.closed_at or t.created_at).astimezone(NY).date().isoformat(),
    "expiration_mode": lambda t: t.expiration_mode or "unknown",
    "dte": lambda t: str((t.expiration - t.created_at.astimezone(NY).date()).days) + "d" if t.expiration else "unknown",
    "strike_mode": lambda t: t.strike_mode or "unknown",
    "exit_reason": lambda t: t.exit_reason or "unknown",
}


def compute(trades: Iterable[Trade]) -> dict:
    ts = sorted(trades, key=lambda t: (t.closed_at, t.id))
    pnls = [t.realized_pnl or 0.0 for t in ts]
    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p < 0]
    gross_profit = sum(wins)
    gross_loss = sum(losses)

    peak = cum = max_dd = 0.0
    for p in pnls:
        cum += p
        peak = max(peak, cum)
        max_dd = max(max_dd, peak - cum)

    win_streak = loss_streak = 0
    for p in reversed(pnls):
        if p > 0 and loss_streak == 0:
            win_streak += 1
        elif p < 0 and win_streak == 0:
            loss_streak += 1
        else:
            break

    durations = [
        (t.exit_timestamp - t.entry_timestamp).total_seconds() for t in ts if t.exit_timestamp and t.entry_timestamp
    ]
    n = len(pnls)
    return {
        "total_trades": n,
        "wins": len(wins),
        "losses": len(losses),
        "breakeven": n - len(wins) - len(losses),
        "win_rate": round(len(wins) / n * 100, 2) if n else None,
        "gross_profit": round(gross_profit, 2),
        "gross_loss": round(gross_loss, 2),
        "net_pnl": round(gross_profit + gross_loss, 2),
        "average_win": round(gross_profit / len(wins), 2) if wins else None,
        "average_loss": round(gross_loss / len(losses), 2) if losses else None,
        "profit_factor": round(gross_profit / -gross_loss, 3) if gross_loss else None,
        "largest_win": round(max(wins), 2) if wins else None,
        "largest_loss": round(min(losses), 2) if losses else None,
        "max_drawdown": round(max_dd, 2),
        "current_win_streak": win_streak,
        "current_loss_streak": loss_streak,
        "average_time_in_trade_seconds": round(sum(durations) / len(durations), 1) if durations else None,
    }


def grouped(trades: Iterable[Trade], by: str) -> dict[str, dict]:
    key = GROUPERS[by]
    buckets: dict[str, list[Trade]] = defaultdict(list)
    for t in trades:
        buckets[key(t)].append(t)
    return {k: compute(v) for k, v in sorted(buckets.items())}
