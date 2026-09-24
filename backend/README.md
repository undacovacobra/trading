# Options Bridge — TradingView futures signals → options execution

This service receives deterministic LONG / SHORT / EXIT / REVERSE signals from a
TradingView Pine futures strategy. It expresses each signal as a long option
(NQ LONG → QQQ call, NQ SHORT → QQQ put by default). The trade is then either
simulated in a persistent paper account (**OBSERVE**), displayed without acting
(**DRY_RUN**), or sent to a Robinhood Agentic account (**LIVE**).

TradingView makes every strategy decision. No AI or discretion sits in the trade
path: the same signal, configuration and quotes always produce the same action.

```
TradingView alert ─► POST /api/webhooks/tradingview ─► auth / validate / dedupe (event_id)
   ─► stored event ─► worker ─► trade state machine ─► option selector ─► risk + sizing
   ─► TradeIntent ─► PaperExecutor │ DryRunExecutor │ RobinhoodExecutor ─► DB ─► dashboard
```

There is exactly one engine (`services/trade_manager.py`). The only thing that
changes between modes is the executor that receives the `TradeIntent`.

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Webhook → DB → dashboard, dedupe, entry/exit matching | ✅ done |
| 2 | Option selection, "I WOULD BUY" (DRY_RUN mode) | ✅ done (selection logic); real quotes need the Robinhood read mapping, see below |
| 3 | Full OBSERVE: paper account, ledger, fills, P&L, stats, equity curve | ✅ done |
| 4 | Robinhood LIVE adapter | 🟡 scaffolded; **LIVE cannot be enabled yet** |
| 5–6 | Small live validation, advanced features | not started |

**Important:** the only market-data source that works today is the **simulated**
chain: Black-Scholes prices with a 2% spread. It is good for testing the plumbing,
but its P&L means nothing about real option economics. The dashboard shows a
yellow **SIMULATED MARKET DATA** chip whenever it is active. Before shadow
testing (section 55 of the spec), map the Robinhood read tools as described below.

## Run locally

```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env        # set WEBHOOK_SECRET and DASHBOARD_PASSWORD
uvicorn app.main:app --port 8000
```

Open http://localhost:8000 and log in with `DASHBOARD_USER` / `DASHBOARD_PASSWORD`.
Use **Settings → Send a test signal** to try the full flow without TradingView.

Tests (this covers acceptance tests 1–10 from the spec plus the safety properties):

```bash
pytest
```

Database migrations run automatically on startup (Alembic, `migrations/`).
SQLite is the default; set `DATABASE_URL=postgresql+psycopg://…` for PostgreSQL.

## Deploy (HTTPS on 443)

TradingView only posts to ports 80/443 and gives up after about 3 seconds.
`docker-compose.yml` runs Caddy (automatic TLS) → app → PostgreSQL:

```bash
cd backend
cp .env.example .env   # set WEBHOOK_SECRET, DASHBOARD_PASSWORD, POSTGRES_PASSWORD, DOMAIN
docker compose up -d --build
```

Point your DNS `DOMAIN` at the host. Optionally set `TRADINGVIEW_IP_ALLOWLIST=true`.
Run a **single** app process. The engine serialises trade work in-process.

## TradingView alert messages

Webhook URL: `https://YOUR_DOMAIN/api/webhooks/tradingview`

Each alert message must be JSON with a unique `event_id`. Build it from the
bar time so that a re-fired alert is recognised as a duplicate. Keep the secret
out of your Pine source if the script is shared; paste it into the alert message.

```pine
// Entry
alert('{"schema_version":1,"secret":"' + webhookSecret + '",' +
      '"event_id":"rev-' + syminfo.ticker + '-' + str.tostring(time) + '-long",' +
      '"strategy_id":"reversal_v6","symbol":"' + syminfo.tickerid + '","action":"ENTER_LONG",' +
      '"signal_time":"' + str.format_time(timenow, "yyyy-MM-dd'T'HH:mm:ssZ", "UTC") + '",' +
      '"trade_id":"' + tradeId + '",' +
      '"entry_price":' + str.tostring(entryPx) + ',"stop_price":' + str.tostring(stopPx) +
      ',"target_price":' + str.tostring(targetPx) + ',"metadata":{"signal_type":"reversal","timeframe":"' + timeframe.period + '"}}',
      alert.freq_once_per_bar_close)

// Exit (reason = TARGET / STOP / BREAKEVEN / SESSION_END / ...)
alert('{"schema_version":1,"secret":"' + webhookSecret + '",' +
      '"event_id":"rev-' + syminfo.ticker + '-' + str.tostring(time) + '-exit-long",' +
      '"strategy_id":"reversal_v6","symbol":"' + syminfo.tickerid + '","action":"EXIT_LONG",' +
      '"trade_id":"' + tradeId + '","futures_price":' + str.tostring(close) + ',"reason":"TARGET"}',
      alert.freq_once_per_bar_close)
```

Supported actions: `ENTER_LONG`, `ENTER_SHORT`, `EXIT_LONG`, `EXIT_SHORT`,
`CLOSE_ALL`, `REVERSE_LONG`, `REVERSE_SHORT`. When `trade_id` is sent, an exit
only closes the position opened with that same id. Without it, the exit closes
the open position for that strategy and direction.

Responses: `200 accepted`, `200 duplicate` (no trade), `401` bad secret,
`400` malformed JSON, `413` too large, `422` invalid or unsupported signal,
`429` rate-limited. Every rejection is recorded in the system log, never with the secret.

## Behaviour reference

**Option selection** is configured per strategy in the dashboard.
- Expiration: `SAME_DAY` (0DTE), `NEXT_AVAILABLE`, `EXACT_DTE` or `MIN_DTE`.
  DTE is counted in trading sessions using the NYSE holiday and early-close calendar.
- Strike: `ATM`, `DELTA`, `PREMIUM`, `OTM_STRIKES`, `OTM_PERCENT` or `FIXED_OFFSET`.
- Filters: min bid, max ask, max spread %, optional open-interest and volume minimums.
- If the chosen contract fails a filter, the trade is **skipped** with a reason
  code such as `SPREAD_TOO_WIDE`. A different contract is used only when
  `fallback_policy = NEAREST_PASSING` is set.

**Sizing** can be fixed dollars (`floor(budget / (limit × 100))`, never above
budget), fixed contracts, or a percent of equity. The result is capped by the
strategy max contracts, the global max contracts, and the global max trade cost.

**Paper fills** buy at ask + entry slippage and sell at bid − exit slippage.
The bid, ask, mid, quote time, slippage and fill are all stored at the moment of
the signal and never reconstructed later. Open positions are marked at the
**bid**. Cash is the sum of the paper ledger (DEPOSIT / OPTION_PURCHASE /
OPTION_SALE / FEE). *Run consistency check* verifies that ledger, trades and cash agree.

**Risk limits** (entries only; **exits are never blocked**):
- per trade: max trade cost, max contracts, max spread
- per day and portfolio: daily loss, trades per day, consecutive losses, open
  positions, total exposure
- timing: entry window, latest 0DTE entry. Both shift earlier automatically on early-close days.

**Controls**
- *Pause new entries*: rejects entries, keeps processing exits.
- *Emergency close*: pauses, then closes every position this app owns. It never
  touches "all QQQ options".
- *System out of sync* (reconciliation mismatch, or an interrupted event):
  disables entries until an operator clears it.

**Restarts.** Stored events survive restarts. An event that was interrupted after
recording trade state is **not** replayed; it marks the system out of sync
instead. LIVE never silently resumes. After a restart it comes back paused if live
positions are open, otherwise in OBSERVE, unless `LIVE_RESUME_ON_RESTART=true`.

## Why OBSERVE cannot place orders

The protection is structural. It does not rely on a flag:
- `PaperExecutor` is built with only a clock. The OBSERVE code path never imports
  any Robinhood trading client (`tests/test_safety.py` checks this with an AST scan).
- `RobinhoodReadClient` refuses `review/place/cancel_option_order`.
- `RobinhoodTradingClient` requires a `LiveAuthorization`, and only
  `executors/robinhood.build_live_executor()` issues one. That function requires
  `LIVE_TRADING=true`, the typed dashboard confirmation, and a verified Agentic account ID.
- Test signals from the dashboard are refused in LIVE mode.

## Finishing Phase 4: the Robinhood adapter

The spec says not to guess MCP parameter formats, so the mapping is left
explicit rather than invented:

1. Connect your Robinhood Agentic account's MCP server and set `ROBINHOOD_MCP_URL`
   and `ROBINHOOD_MCP_TOKEN`.
2. Dump the real tool schemas. This is read-only and places no orders:
   `python -m app.brokers.robinhood_mcp inspect --out robinhood_tools.json`
3. Implement the `SchemaNotVerified` stubs from those schemas:
   - `market_data/robinhood.py`: underlying price, expirations, chain, quotes (read tools)
   - `brokers/robinhood_mcp.py`: `get_account` (must return `id`, `is_agentic`,
     `equity`, `buying_power`), positions, orders, and review/place/cancel with the
     normalised shapes that `executors/robinhood.py` expects.
4. Run OBSERVE with `MARKET_DATA_PROVIDER=robinhood` during market hours (Phases 2–3
   on real quotes). Then set `ROBINHOOD_AGENTIC_ACCOUNT` and `LIVE_TRADING=true`
   and start with small limits (Phase 5).

## Layout

```
app/
  api/            tradingview.py (webhook) · dashboard.py (reads) · settings.py (config/controls) · security.py
  services/       trade_manager (engine) · option_selector · position_sizer · risk_manager
                  paper_account (ledger) · state_machine · worker · system_control · stats
                  market_calendar · config_store · notifier · symbols
  executors/      base · paper · dry_run · robinhood · registry
  market_data/    base · simulated · robinhood (read-only)
  brokers/        robinhood_mcp (read client / trading client / inspect CLI)
  models/ schemas/ static/ (dashboard)
migrations/       Alembic
tests/            acceptance, safety, security, selection/calendar
```
