# trading

- `backend/` — **Options Bridge**: TradingView futures signals → options trades (OBSERVE paper
  account / DRY_RUN / Robinhood Agentic LIVE). See [backend/README.md](backend/README.md).
- `main.py`, `browser_trader.py`, `setup_and_run.py` — the earlier TradingView → Tradovate
  futures webhook (browser automation). Unchanged and independent of `backend/`.
