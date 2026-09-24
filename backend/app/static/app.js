"use strict";
// Options Bridge dashboard. Plain JS, no build step. All server strings are
// inserted with textContent (never innerHTML) because they include webhook data.

const $ = (id) => document.getElementById(id);
const state = { tab: "overview", equityRange: "all", config: null, strategy: null, status: null };

function el(tag, attrs = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") n.className = v;
    else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
    else n.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    n.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return n;
}

async function api(path, opts = {}) {
  const r = await fetch(path, {
    credentials: "same-origin",
    headers: opts.body ? { "content-type": "application/json" } : {},
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  let data = null;
  try { data = await r.json(); } catch { /* empty */ }
  if (!r.ok) {
    const d = data && data.detail;
    const msg = typeof d === "string" ? d : d && d.message ? d.message : Array.isArray(d) ? d.map((e) => `${e.loc.slice(-1)[0]}: ${e.msg}`).join("; ") : r.statusText;
    throw new Error(msg);
  }
  return data;
}

// ---------------------------------------------------------------- formatting
const usd = (v, sign = false) => v == null ? "—" : (sign && v > 0 ? "+" : v < 0 ? "−" : "") + "$" + Math.abs(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const px = (v) => v == null ? "—" : Number(v).toFixed(2);
const pct = (v, sign = true) => v == null ? "—" : (sign && v > 0 ? "+" : v < 0 ? "−" : "") + Math.abs(v).toFixed(2) + "%";
const cls = (v) => v == null ? "" : v > 0 ? "pos" : v < 0 ? "neg" : "";
const nyTime = (iso) => iso ? new Date(iso).toLocaleString("en-US", { timeZone: "America/New_York", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }) : "—";
const nyClock = (iso) => iso ? new Date(iso).toLocaleTimeString("en-US", { timeZone: "America/New_York", hour12: false }) : "—";
const optLabel = (t) => t.option_underlying && t.strike != null ? `${t.option_underlying} ${+t.strike}${t.option_type ? t.option_type[0] : ""}` : "—";
const dur = (s) => s == null ? "—" : s < 60 ? `${Math.round(s)}s` : s < 3600 ? `${Math.round(s / 60)}m` : `${(s / 3600).toFixed(1)}h`;

// ---------------------------------------------------------------- status/header
const MODE_TEXT = {
  OBSERVE: "MODE: OBSERVE — paper trading, no orders are sent",
  DRY_RUN: "MODE: DRY RUN — shows what would be done, no positions created",
  LIVE: "⚠ MODE: LIVE — REAL MONEY — orders go to Robinhood",
};

function renderStatus(s) {
  state.status = s;
  const b = $("mode-banner");
  b.className = "mode-banner " + s.mode;
  b.textContent = MODE_TEXT[s.mode] + (s.paused ? " · NEW ENTRIES PAUSED" : "");
  document.title = `${s.mode} · Options Bridge`;

  const chips = $("chips");
  chips.replaceChildren(...[
    s.out_of_sync && el("span", { class: "chip crit", title: s.out_of_sync_reason || "" }, "SYSTEM OUT OF SYNC — entries disabled"),
    s.paused && el("span", { class: "chip warn" }, "Entries paused"),
    s.market_data.simulated && el("span", { class: "chip warn", title: "Quotes are synthetic; paper results are not meaningful" }, "SIMULATED MARKET DATA"),
    el("span", { class: "chip" }, "Data: " + s.market_data.provider),
    el("span", { class: "chip" }, "Account: " + s.account_label),
    el("span", { class: "chip" }, s.live_trading_env ? "LIVE_TRADING env: on" : "LIVE_TRADING env: off"),
    el("span", { class: "chip" }, (s.market.trading_day ? "Market day, close " + (s.market.session_close || "").slice(0, 5) : "Market closed today") + " · " + nyClock(s.server_time) + " ET"),
    s.worker_queue > 0 && el("span", { class: "chip" }, `${s.worker_queue} queued`),
  ].filter(Boolean));
  const p = $("btn-pause");
  p.textContent = s.paused ? "Resume new entries" : "Pause new entries";
  p.classList.toggle("paused", s.paused);
  if (s.out_of_sync) $("mode-hint").textContent = "Out of sync: " + (s.out_of_sync_reason || "");
}

$("btn-pause").addEventListener("click", async () => {
  const paused = state.status && state.status.paused;
  try { await api(paused ? "/api/control/resume" : "/api/control/pause", { method: "POST" }); } catch (e) { alert(e.message); }
  refresh();
});

$("btn-emergency").addEventListener("click", async () => {
  const c = prompt("EMERGENCY CLOSE attempts to close every position owned by this application and pauses new entries.\nType CLOSE to confirm.");
  if (c == null) return;
  try {
    const r = await api("/api/control/emergency-close", { method: "POST", body: { confirmation: c } });
    alert(`Requested ${r.requested}, closed ${r.closed}` + (r.failed.length ? `\nFailed: ${r.failed.map((f) => f.code + " " + f.message).join("\n")}` : ""));
  } catch (e) { alert(e.message); }
  refresh();
});

// ---------------------------------------------------------------- overview
function tile(k, v, c = "") { return el("div", { class: "tile" }, el("div", { class: "k" }, k), el("div", { class: "v " + c }, v)); }

function renderAccount(a) {
  const p = a.paper;
  $("account-title").textContent = "Paper account";
  const tiles = [
    tile("Starting balance", usd(p.starting_balance)),
    tile("Current equity", usd(p.equity)),
    tile("Available cash", usd(p.available_cash)),
    tile("Open positions", usd(p.market_value)),
    tile("Realized P&L", usd(p.realized_pnl, true), cls(p.realized_pnl)),
    tile("Unrealized P&L", usd(p.unrealized_pnl, true), cls(p.unrealized_pnl)),
    tile("Total return", pct(p.total_return_pct), cls(p.total_return_pct)),
    tile("Drawdown", usd(p.drawdown)),
    tile("Max drawdown", usd(p.max_drawdown)),
  ];
  if (a.live) tiles.push(tile("Robinhood Agentic", a.live.error ? a.live.error : `${usd(a.live.equity)} · BP ${usd(a.live.buying_power)}`));
  $("account-tiles").replaceChildren(...tiles);
}

function renderPositions(list) {
  const box = $("positions");
  if (!list.length) { box.replaceChildren(el("div", { class: "empty" }, "No open positions.")); return; }
  box.replaceChildren(...list.map((t) => el("div", { class: "pos-card" },
    el("h3", {}, `${optLabel(t)}  ×${t.quantity}`, el("span", { class: "tag " + t.mode }, t.mode), el("span", { class: "sub" }, `exp ${t.expiration} · ${t.state}`)),
    el("div", { class: "pos-cols" },
      el("div", {},
        el("div", { class: "sub" }, `FUTURES SIGNAL · ${t.strategy_id}`),
        el("dl", { class: "kv" },
          el("dt", {}, "Direction"), el("dd", {}, `${t.futures_symbol} ${t.futures_direction}`),
          el("dt", {}, "Entry"), el("dd", {}, t.futures_entry ?? "—"),
          el("dt", {}, "Stop"), el("dd", {}, t.futures_stop ?? "—"),
          el("dt", {}, "Target"), el("dd", {}, t.futures_target ?? "—"),
          el("dt", {}, "Opened"), el("dd", {}, nyTime(t.entry_timestamp)),
        )),
      el("div", {},
        el("div", { class: "sub" }, "OPTION POSITION"),
        el("dl", { class: "kv" },
          el("dt", {}, "Entry fill"), el("dd", {}, px(t.entry_fill)),
          el("dt", {}, "Bid / Ask"), el("dd", {}, `${px(t.current_bid)} / ${px(t.current_ask)}`),
          el("dt", {}, "Cost"), el("dd", {}, usd(t.gross_cost)),
          el("dt", {}, "Value (bid)"), el("dd", {}, usd(t.current_value)),
          el("dt", {}, "Quote"), el("dd", {}, nyClock(t.current_quote_time)),
        )),
    ),
    el("div", { class: "pnl-big " + cls(t.unrealized_pnl) }, `${usd(t.unrealized_pnl, true)}  ${pct(t.unrealized_pct)}`),
  )));
}

// Equity curve: single series, 2px line, crosshair + tooltip, table view.
function renderEquity(data) {
  const box = $("equity-chart");
  const pts = data.points.map((p) => ({ t: new Date(p.at).getTime(), v: p.equity, at: p.at }));
  $("equity-table").replaceChildren(el("div", { class: "table-wrap" }, el("table", {},
    el("thead", {}, el("tr", {}, el("th", {}, "Time (ET)"), el("th", { class: "r" }, "Equity"), el("th", { class: "r" }, "Cash"), el("th", { class: "r" }, "Drawdown"))),
    el("tbody", {}, data.points.slice(-200).reverse().map((p) => el("tr", {}, el("td", {}, nyTime(p.at)), el("td", { class: "r" }, usd(p.equity)), el("td", { class: "r" }, usd(p.cash)), el("td", { class: "r" }, usd(p.drawdown))))))));
  if (pts.length < 2) { box.replaceChildren(el("div", { class: "empty" }, "Not enough equity history yet.")); return; }

  const W = box.clientWidth || 800, H = box.clientHeight || 260, m = { l: 64, r: 12, t: 10, b: 24 };
  const t0 = pts[0].t, t1 = pts[pts.length - 1].t;
  let lo = Math.min(data.starting_balance, ...pts.map((p) => p.v)), hi = Math.max(data.starting_balance, ...pts.map((p) => p.v));
  const pad = Math.max((hi - lo) * 0.1, 1); lo -= pad; hi += pad;
  const x = (t) => m.l + (t1 === t0 ? 0 : (t - t0) / (t1 - t0)) * (W - m.l - m.r);
  const y = (v) => m.t + (1 - (v - lo) / (hi - lo)) * (H - m.t - m.b);
  const NS = "http://www.w3.org/2000/svg";
  const s = (tag, attrs) => { const n = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v); return n; };
  const svg = s("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": `Paper equity from ${usd(pts[0].v)} to ${usd(pts[pts.length - 1].v)}` });
  const axis = s("g", { class: "axis" });
  for (let i = 0; i <= 4; i++) {
    const v = lo + (hi - lo) * i / 4, yy = y(v);
    axis.append(s("line", { class: "gridline", x1: m.l, x2: W - m.r, y1: yy, y2: yy }));
    const tx = s("text", { x: m.l - 8, y: yy + 4, "text-anchor": "end" }); tx.textContent = "$" + Math.round(v).toLocaleString(); axis.append(tx);
  }
  for (const [tt, anchor] of [[t0, "start"], [t1, "end"]]) {
    const tx = s("text", { x: x(tt), y: H - 6, "text-anchor": anchor }); tx.textContent = nyTime(new Date(tt).toISOString()); axis.append(tx);
  }
  svg.append(axis);
  svg.append(s("line", { class: "baseline", x1: m.l, x2: W - m.r, y1: y(data.starting_balance), y2: y(data.starting_balance) }));
  svg.append(s("path", { class: "line", d: pts.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join("") }));
  const cross = s("line", { class: "crosshair", y1: m.t, y2: H - m.b, visibility: "hidden" });
  const dot = s("circle", { class: "dot", r: 4, visibility: "hidden" });
  svg.append(cross, dot);
  const hit = s("rect", { x: m.l, y: 0, width: W - m.l - m.r, height: H, fill: "transparent" });
  svg.append(hit);
  const tip = $("tooltip");
  hit.addEventListener("pointermove", (ev) => {
    const r = svg.getBoundingClientRect();
    const vx = (ev.clientX - r.left) * (W / r.width);
    let best = pts[0];
    for (const p of pts) if (Math.abs(x(p.t) - vx) < Math.abs(x(best.t) - vx)) best = p;
    cross.setAttribute("x1", x(best.t)); cross.setAttribute("x2", x(best.t)); cross.setAttribute("visibility", "visible");
    dot.setAttribute("cx", x(best.t)); dot.setAttribute("cy", y(best.v)); dot.setAttribute("visibility", "visible");
    tip.replaceChildren(el("div", {}, nyTime(best.at) + " ET"), el("strong", {}, usd(best.v)), el("div", { class: cls(best.v - data.starting_balance) }, usd(best.v - data.starting_balance, true) + " vs start"));
    tip.hidden = false;
    tip.style.left = Math.min(ev.clientX + 14, window.innerWidth - 180) + "px";
    tip.style.top = ev.clientY - 10 + "px";
  });
  hit.addEventListener("pointerleave", () => { tip.hidden = true; cross.setAttribute("visibility", "hidden"); dot.setAttribute("visibility", "hidden"); });
  box.replaceChildren(svg);
}

$("equity-range").addEventListener("click", (e) => {
  const r = e.target.dataset.range; if (!r) return;
  state.equityRange = r;
  for (const b of $("equity-range").children) b.classList.toggle("on", b.dataset.range === r);
  loadEquity();
});
$("equity-table-toggle").addEventListener("click", () => {
  const t = $("equity-table"); t.hidden = !t.hidden;
  $("equity-table-toggle").textContent = t.hidden ? "Table view" : "Hide table";
});

// ---------------------------------------------------------------- signals
function describeSignal(e) {
  const t = e.trades[0];
  if (e.result_code === "DRY_RUN" && t && t.intent) {
    const i = t.intent;
    return `I WOULD BUY ${i.quantity} × ${i.options_underlying} ${+i.strike}${i.option_type[0]} exp ${i.expiration} · bid ${px(i.estimated_bid)} ask ${px(i.estimated_ask)} · est. ${usd(i.estimated_cost)}`;
  }
  return e.result_message || "";
}

function signalRows(list, compact) {
  const head = el("tr", {}, el("th", {}, "Received (ET)"), el("th", {}, "Action"), el("th", {}, "Strategy"), el("th", {}, "Symbol"), el("th", {}, "Status"), el("th", {}, "Result"), !compact && el("th", {}, "Event ID"));
  const rows = [];
  for (const e of list) {
    const row = el("tr", { class: "clickable" },
      el("td", { class: "num" }, nyTime(e.received_at)),
      el("td", {}, e.action + (e.payload && e.payload.reason ? ` (${e.payload.reason})` : "")),
      el("td", {}, e.strategy_id), el("td", {}, e.symbol),
      el("td", {}, el("span", { class: "status " + e.status }, e.status), e.duplicate_count ? el("span", { class: "muted" }, ` +${e.duplicate_count} dup`) : null),
      el("td", {}, el("strong", {}, e.result_code || ""), " ", describeSignal(e)),
      !compact && el("td", { class: "muted" }, e.event_id));
    const detail = el("tr", { hidden: true }, el("td", { colspan: 7, class: "detail" }, JSON.stringify({ payload: e.payload, trades: e.trades }, null, 2)));
    row.addEventListener("click", () => { detail.hidden = !detail.hidden; });
    rows.push(row, detail);
  }
  return [el("thead", {}, head), el("tbody", {}, rows)];
}

// ---------------------------------------------------------------- history
function renderStats(st) {
  const o = st.overall;
  $("stats-tiles").replaceChildren(
    tile("Trades", o.total_trades), tile("Win rate", o.win_rate == null ? "—" : o.win_rate.toFixed(1) + "%"),
    tile("Wins / losses", `${o.wins} / ${o.losses}`), tile("Net P&L", usd(o.net_pnl, true), cls(o.net_pnl)),
    tile("Gross profit", usd(o.gross_profit)), tile("Gross loss", usd(o.gross_loss)),
    tile("Avg win", usd(o.average_win)), tile("Avg loss", usd(o.average_loss)),
    tile("Profit factor", o.profit_factor ?? "—"), tile("Largest win", usd(o.largest_win)), tile("Largest loss", usd(o.largest_loss)),
    tile("Max drawdown", usd(o.max_drawdown)), tile("Streak", o.current_win_streak ? `${o.current_win_streak} W` : o.current_loss_streak ? `${o.current_loss_streak} L` : "—"),
    tile("Avg time in trade", dur(o.average_time_in_trade_seconds)),
  );
  const g = $("stats-groups");
  if (!st.groups) { g.replaceChildren(); return; }
  g.replaceChildren(el("div", { class: "table-wrap" }, el("table", {},
    el("thead", {}, el("tr", {}, ["Group", "Trades", "Win %", "Net P&L", "Avg win", "Avg loss", "PF", "Max DD"].map((h, i) => el("th", { class: i ? "r" : "" }, h)))),
    el("tbody", {}, Object.entries(st.groups).map(([k, v]) => el("tr", {},
      el("td", {}, k), el("td", { class: "r" }, v.total_trades), el("td", { class: "r" }, v.win_rate == null ? "—" : v.win_rate.toFixed(1)),
      el("td", { class: "r " + cls(v.net_pnl) }, usd(v.net_pnl, true)), el("td", { class: "r" }, usd(v.average_win)), el("td", { class: "r" }, usd(v.average_loss)),
      el("td", { class: "r" }, v.profit_factor ?? "—"), el("td", { class: "r" }, usd(v.max_drawdown))))))));
}

function renderTrades(list) {
  const t = $("trades-table");
  const head = el("tr", {}, ["Time (ET)", "Strategy", "Futures signal", "Option", "Qty", "Entry", "Exit", "P&L", "Return", "Exit reason", "Mode"].map((h, i) => el("th", { class: [4, 5, 6, 7, 8].includes(i) ? "r" : "" }, h)));
  const rows = [];
  for (const x of list) {
    const row = el("tr", { class: "clickable" },
      el("td", { class: "num" }, nyTime(x.entry_timestamp)), el("td", {}, x.strategy_id),
      el("td", {}, `${x.futures_root || x.futures_symbol} ${x.futures_direction}`), el("td", {}, `${optLabel(x)} ${x.expiration || ""}`),
      el("td", { class: "r" }, x.quantity), el("td", { class: "r" }, px(x.entry_fill)), el("td", { class: "r" }, px(x.exit_fill)),
      el("td", { class: "r " + cls(x.realized_pnl) }, usd(x.realized_pnl, true)), el("td", { class: "r " + cls(x.return_percent) }, pct(x.return_percent)),
      el("td", {}, x.exit_reason || ""), el("td", {}, el("span", { class: "tag " + x.mode }, x.mode)));
    const detail = el("tr", { hidden: true }, el("td", { colspan: 11, class: "detail" }, "Loading…"));
    row.addEventListener("click", async () => {
      detail.hidden = !detail.hidden;
      if (!detail.hidden) {
        const d = await api(`/api/trades/${x.trade_uid}`);
        detail.firstChild.textContent = JSON.stringify({ timing_ms: d.timing_ms, entry: { bid: d.entry_bid, ask: d.entry_ask, mid: d.entry_mid, fill: d.entry_fill }, exit: { bid: d.exit_bid, ask: d.exit_ask, fill: d.exit_fill }, orders: d.orders, transitions: d.transitions, notes: d.selection_notes, source_event_id: d.source_event_id, exit_event_id: d.exit_event_id }, null, 2);
      }
    });
    rows.push(row, detail);
  }
  t.replaceChildren(el("thead", {}, head), el("tbody", {}, rows.length ? rows : el("tr", {}, el("td", { colspan: 11, class: "empty" }, "No closed trades yet."))));
}

function renderLog(list) {
  $("log-table").replaceChildren(
    el("thead", {}, el("tr", {}, el("th", {}, "Time (ET)"), el("th", {}, "Level"), el("th", {}, "Code"), el("th", {}, "Message"), el("th", {}, "Event"))),
    el("tbody", {}, list.map((e) => el("tr", {}, el("td", { class: "num" }, nyTime(e.at)), el("td", { class: "lvl-" + e.level }, e.level), el("td", {}, e.code), el("td", {}, e.message), el("td", { class: "muted" }, e.event_id || "")))));
}

// ---------------------------------------------------------------- settings
const STRATEGY_FIELDS = [
  ["General"],
  ["enabled", "Enabled", "bool"],
  ["options_underlying", "Options underlying (blank = symbol map)", "text"],
  ["long_option_type", "Long signal buys", ["CALL", "PUT"]],
  ["short_option_type", "Short signal buys", ["PUT", "CALL"]],
  ["Expiration"],
  ["expiration_mode", "Expiration mode", ["SAME_DAY", "NEXT_AVAILABLE", "EXACT_DTE", "MIN_DTE"]],
  ["dte", "DTE (trading days, for EXACT/MIN)", "int"],
  ["Strike selection"],
  ["strike_mode", "Strike mode", ["ATM", "DELTA", "PREMIUM", "OTM_STRIKES", "OTM_PERCENT", "FIXED_OFFSET"]],
  ["target_delta", "Target delta (DELTA)", "num"],
  ["target_premium", "Target premium $ (PREMIUM)", "num"],
  ["otm_strikes", "Strikes OTM (OTM_STRIKES, −=ITM)", "int"],
  ["otm_percent", "% OTM (OTM_PERCENT, −=ITM)", "num"],
  ["strike_offset", "$ offset (FIXED_OFFSET)", "num"],
  ["fallback_policy", "If contract fails filters", ["NONE", "NEAREST_PASSING"]],
  ["fallback_max_steps", "Fallback max steps", "int"],
  ["Liquidity filters"],
  ["filters.min_bid", "Minimum bid $", "num"],
  ["filters.max_ask", "Maximum ask $", "num"],
  ["filters.max_spread_pct", "Max bid/ask spread %", "num"],
  ["filters.min_open_interest", "Min open interest (blank = off)", "optint"],
  ["filters.min_volume", "Min volume (blank = off)", "optint"],
  ["Sizing"],
  ["sizing_mode", "Trade sizing", ["FIXED_DOLLARS", "FIXED_CONTRACTS", "PERCENT_EQUITY"]],
  ["position_size_dollars", "Dollar amount $", "num"],
  ["fixed_contracts", "Fixed contracts", "int"],
  ["percent_equity", "% of equity", "num"],
  ["max_contracts", "Maximum contracts", "int"],
  ["Conflicts"],
  ["opposite_entry_policy", "Entry while opposite open", ["REJECT", "REVERSE"]],
  ["reverse_when_flat", "REVERSE_* with no position opens new side", "bool"],
];
const RISK_FIELDS = [
  ["Per trade"],
  ["max_trade_cost", "Maximum trade cost $", "num"],
  ["max_contracts_per_trade", "Max contracts per trade", "int"],
  ["max_spread_pct", "Global max spread %", "num"],
  ["Daily / portfolio"],
  ["max_daily_loss", "Maximum daily loss $", "num"],
  ["max_daily_trades", "Maximum trades per day", "int"],
  ["max_consecutive_losses", "Max consecutive losses (today)", "int"],
  ["max_open_positions", "Max open positions", "int"],
  ["max_total_exposure", "Max total option exposure $", "num"],
  ["Hours (America/New_York; shifted on early closes)"],
  ["trading_start", "Entries allowed from", "time"],
  ["trading_end", "Entries allowed until", "time"],
  ["latest_0dte_entry", "Latest 0DTE entry", "time"],
  ["Paper fill model"],
  ["entry_slippage", "Entry slippage $/share (buy = ask + x)", "num"],
  ["exit_slippage", "Exit slippage $/share (sell = bid − x)", "num"],
  ["fee_per_contract", "Fee per contract $", "num"],
];
const SYSTEM_FIELDS = [
  ["quote_interval_seconds", "Mark-to-market interval (s)", "int"],
  ["auto_register_strategies", "Auto-register unknown strategy_ids", "bool"],
  ["symbol_map", "Futures → options map (NQ=QQQ, ES=SPY…)", "map"],
];

const getPath = (o, p) => p.split(".").reduce((a, k) => (a == null ? a : a[k]), o);
function setPath(o, p, v) { const ks = p.split("."); let a = o; for (const k of ks.slice(0, -1)) a = a[k] = a[k] || {}; a[ks[ks.length - 1]] = v; }

function buildForm(form, fields, values) {
  form.replaceChildren();
  let group = form;
  for (const f of fields) {
    if (f.length === 1) { group = el("fieldset", {}, el("legend", {}, f[0])); form.append(group); continue; }
    const [key, label, type] = f;
    const v = getPath(values, key);
    let input;
    if (Array.isArray(type)) input = el("select", {}, type.map((o) => el("option", { selected: o === v }, o)));
    else if (type === "bool") input = el("select", {}, el("option", { value: "true", selected: v === true }, "Yes"), el("option", { value: "false", selected: v === false }, "No"));
    else if (type === "map") input = el("input", { value: Object.entries(v || {}).map(([a, b]) => `${a}=${b}`).join(", ") });
    else input = el("input", { type: type === "time" ? "time" : type === "text" ? "text" : "number", step: type === "num" ? "any" : "1", value: v ?? "" });
    input.dataset.key = key; input.dataset.type = Array.isArray(type) ? "enum" : type;
    group.append(el("label", { class: type === "map" ? "span2" : "" }, label, input));
  }
}

function readForm(form, base) {
  const out = structuredClone(base);
  for (const input of form.querySelectorAll("[data-key]")) {
    const { key, type } = input.dataset; const raw = input.value.trim();
    let v = raw;
    if (type === "bool") v = raw === "true";
    else if (type === "int") v = parseInt(raw, 10);
    else if (type === "num") v = parseFloat(raw);
    else if (type === "optint") v = raw === "" ? null : parseInt(raw, 10);
    else if (type === "text") v = raw === "" ? null : raw;
    else if (type === "map") v = Object.fromEntries(raw.split(",").map((p) => p.split("=").map((s) => s.trim())).filter((p) => p.length === 2 && p[0] && p[1]));
    setPath(out, key, v);
  }
  return out;
}

async function flash(id, fn) {
  const m = $(id); m.className = "save-msg"; m.textContent = "Saving…";
  try { await fn(); m.textContent = "Saved ✓"; } catch (e) { m.className = "save-msg err"; m.textContent = e.message; }
}

async function loadSettings() {
  const c = await api("/api/config");
  state.config = c;
  const ids = Object.keys(c.strategies);
  const sel = $("strategy-select");
  if (!state.strategy || !ids.includes(state.strategy)) state.strategy = ids[0];
  sel.replaceChildren(...ids.map((id) => el("option", { selected: id === state.strategy }, id)));
  buildForm($("strategy-form"), STRATEGY_FIELDS, c.strategies[state.strategy]);
  buildForm($("risk-form"), RISK_FIELDS, c.risk);
  buildForm($("system-form"), SYSTEM_FIELDS, c.system);
  $("reset-balance").value = c.system.paper_starting_balance;
  renderModePicker(c.system.mode);
}

function renderModePicker(mode) {
  const opts = [["OBSERVE", "Paper account, simulated fills"], ["DRY_RUN", "Show intended trades only"], ["LIVE", "Real orders via Robinhood Agentic"]];
  $("mode-picker").replaceChildren(...opts.map(([m, d]) => el("button", {
    class: "mode-opt" + (m === "LIVE" ? " live" : "") + (m === mode ? " on" : ""),
    onclick: () => changeMode(m),
  }, el("strong", {}, m), el("small", {}, d))));
  $("mode-hint").textContent = "LIVE additionally requires LIVE_TRADING=true on the server and a verified Robinhood Agentic account. The system starts in OBSERVE after restarts unless configured otherwise.";
}

async function changeMode(m) {
  let confirmation = null;
  if (m === "LIVE") {
    confirmation = prompt('LIVE mode sends REAL orders with REAL money.\nType "ENABLE LIVE TRADING" to continue.');
    if (confirmation == null) return;
  }
  try { await api("/api/control/mode", { method: "POST", body: { mode: m, confirmation } }); } catch (e) { alert(e.message); }
  await loadSettings(); refresh();
}

$("strategy-select").addEventListener("change", (e) => { state.strategy = e.target.value; buildForm($("strategy-form"), STRATEGY_FIELDS, state.config.strategies[state.strategy]); });
$("save-strategy").addEventListener("click", () => flash("strategy-msg", async () => {
  const body = readForm($("strategy-form"), state.config.strategies[state.strategy]);
  await api(`/api/config/strategies/${encodeURIComponent(state.strategy)}`, { method: "PUT", body });
  await loadSettings();
}));
$("save-risk").addEventListener("click", () => flash("risk-msg", async () => {
  await api("/api/config/risk", { method: "PUT", body: readForm($("risk-form"), state.config.risk) }); await loadSettings();
}));
$("save-system").addEventListener("click", () => flash("system-msg", async () => {
  const s = readForm($("system-form"), state.config.system);
  await api("/api/config/system", { method: "PUT", body: { quote_interval_seconds: s.quote_interval_seconds, auto_register_strategies: s.auto_register_strategies, symbol_map: s.symbol_map } });
  await loadSettings();
}));
$("reset-paper").addEventListener("click", () => {
  const c = prompt("Reset the paper account? History is kept under the old account; positions must be closed.\nType RESET to confirm.");
  if (c == null) return;
  flash("paper-msg", async () => {
    await api("/api/control/paper-reset", { method: "POST", body: { confirmation: c, starting_balance: parseFloat($("reset-balance").value) || null } });
    refresh();
  });
});
$("reconcile").addEventListener("click", () => flash("paper-msg", async () => {
  const r = await api("/api/control/reconcile", { method: "POST" });
  if (!r.ok) throw new Error("Inconsistent: " + r.problems.join("; "));
}));
$("ts-send").addEventListener("click", () => flash("ts-msg", async () => {
  const body = { action: $("ts-action").value, strategy_id: $("ts-strategy").value.trim(), symbol: $("ts-symbol").value.trim() };
  if ($("ts-reason").value.trim()) body.reason = $("ts-reason").value.trim();
  await api("/api/test-signal", { method: "POST", body });
  setTimeout(refresh, 400);
}));

// ---------------------------------------------------------------- tabs & polling
for (const t of document.querySelectorAll(".tab")) {
  t.addEventListener("click", () => {
    state.tab = t.dataset.tab;
    for (const x of document.querySelectorAll(".tab")) x.classList.toggle("active", x === t);
    for (const p of document.querySelectorAll(".tabpanel")) p.hidden = p.id !== "tab-" + state.tab;
    if (state.tab === "settings") loadSettings();
    refresh();
  });
}
$("stats-mode").addEventListener("change", refresh);
$("stats-group").addEventListener("change", refresh);
$("log-level").addEventListener("change", refresh);

async function loadEquity() { renderEquity(await api("/api/equity?range=" + state.equityRange)); }

let busy = false;
async function refresh() {
  if (busy) return; busy = true;
  try {
    renderStatus(await api("/api/status"));
    if (state.tab === "overview") {
      const [a, p, sig] = await Promise.all([api("/api/account"), api("/api/positions"), api("/api/signals?limit=8")]);
      renderAccount(a); renderPositions(p); $("signals-short").replaceChildren(el("div", { class: "table-wrap" }, el("table", {}, ...signalRows(sig, true))));
      await loadEquity();
    } else if (state.tab === "history") {
      const g = $("stats-group").value;
      const [st, tr] = await Promise.all([api(`/api/stats?mode=${$("stats-mode").value}` + (g ? `&group_by=${g}` : "")), api("/api/trades?limit=300")]);
      renderStats(st); renderTrades(tr);
    } else if (state.tab === "signals") {
      const lvl = $("log-level").value;
      const [sig, log] = await Promise.all([api("/api/signals?limit=200"), api("/api/system-events?limit=200" + (lvl ? "&min_level=" + lvl : ""))]);
      $("signals-table").replaceChildren(...signalRows(sig, false)); renderLog(log);
    }
  } catch (e) {
    const b = $("mode-banner"); b.className = "mode-banner LIVE"; b.textContent = "Dashboard cannot reach the server: " + e.message;
  } finally { busy = false; }
}

refresh();
setInterval(() => { if (!document.hidden && state.tab !== "settings") refresh(); }, 3000);
window.addEventListener("resize", () => { if (state.tab === "overview") loadEquity(); });
