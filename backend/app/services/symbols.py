"""Futures symbol helpers."""

import re

_CONTINUOUS = re.compile(r"\d+!$")
_DATED = re.compile(r"([A-Z]+)[FGHJKMNQUVXZ]\d{1,4}")


def futures_root(symbol: str) -> str:
    """Reduce a TradingView futures ticker to its root.

    "NQ1!" -> "NQ", "CME_MINI:NQ1!" -> "NQ", "NQZ2026" -> "NQ", "MNQU6" -> "MNQ", "ES" -> "ES"
    """
    s = symbol.strip().upper().split(":")[-1]
    s = _CONTINUOUS.sub("", s)
    m = _DATED.fullmatch(s)
    return m.group(1) if m else s
