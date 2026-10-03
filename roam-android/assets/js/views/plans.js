// Plans: a month calendar of trips, plans and busy times; a day view; trips with ideas and events.

import { state } from '../store.js';
import { phone } from '../native.js';
import { $, esc, icon, miles, watchImages, toast, timeLabel, photoSrc } from '../util.js';
import * as data from '../data.js';
import { score, available, diversify } from '../taste.js';
import * as P from '../plans.js';
import { plannedRow, planRow, act } from './cards.js';

const WD = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
let month = null;            // "2026-10"
let tripDay = {};            // trip id -> selected day
let draft = null;            // trip being created or edited
let found = [];              // destination search results
const loadingTrips = new Set();

export function render(root, param, app) {
  const [kind, arg] = (param || '').split('/');
  if (kind === 'day' && /^\d{4}-\d{2}-\d{2}$/.test(arg)) return day(root, arg, app);
  if (kind === 'trip') return trip(root, arg, app);
  if (kind === 'new' || kind === 'edit') return form(root, kind === 'edit' ? arg : null, app);
  return overview(root, app);
}

// ---- Month overview ---------------------------------------------------------------------------

function overview(root, app) {
  P.tidy();
  const now = P.today();
  month = month || now.slice(0, 7);
  const first = P.parse(month + '-01');
  const label = first.toLocaleDateString([], { month: 'long', year: 'numeric' });
  const startPad = first.getDay();
  const days = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  const cal = P.calendarStatus();
  const monthStart = first.getTime(), monthEnd = new Date(first.getFullYear(), first.getMonth() + 1, 1).getTime();
  const busy = new Set(P.calendar(monthStart, monthEnd).filter(r => !r.free).flatMap(r => {
    const [s, e] = P.span(r);
    const out = [];
    for (let t = s; t < e && out.length < 31; t += 86400000) out.push(P.ymd(new Date(t)));
    return out.length ? out : [P.ymd(new Date(s))];
  }));
  const savedEvents = Object.values(state.saved).filter(s => s.type === 'event' && s.start > Date.now() - 86400000);
  const eventDays = new Set(savedEvents.map(e => P.ymd(new Date(e.start))));

  const cells = [];
  for (let i = 0; i < startPad; i++) cells.push('<span class="cal-cell cal-pad"></span>');
  for (let d = 1; d <= days; d++) {
    const date = `${month}-${String(d).padStart(2, '0')}`;
    const t = P.tripOn(date);
    const marks = [
      P.plannedOn(date).length || eventDays.has(date) ? '<i class="mark plan"></i>' : '',
      busy.has(date) ? '<i class="mark busy"></i>' : '',
    ].join('');
    cells.push(`<a class="cal-cell ${date === now ? 'today' : ''} ${date < now ? 'past' : ''} ${t ? 'trip' : ''} ${t && t.start === date ? 'trip-start' : ''} ${t && t.end === date ? 'trip-end' : ''}" href="#/plans/day/${date}" aria-label="${esc(P.longDay(date))}">
      <span>${d}</span><span class="marks">${marks}</span></a>`);
  }

  const upcoming = P.upcomingTrips();
  const soon = [];
  for (const d of P.range(now, P.addDays(now, 30))) {
    for (const id of P.plannedOn(d)) { const it = data.get(id); if (it) soon.push({ d, it }); }
  }
  for (const e of savedEvents) {
    const d = P.ymd(new Date(e.start));
    if (!soon.some(x => x.it.id === e.id)) soon.push({ d, it: e });
  }
  soon.sort((a, b) => a.d.localeCompare(b.d));

  root.innerHTML = `<h1 class="page-title">Plans</h1>
    <section class="card calendar">
      <div class="cal-head">
        <button type="button" class="round small plain" data-month="-1" aria-label="Previous month">${icon('back', 18)}</button>
        <strong>${esc(label)}</strong>
        <button type="button" class="round small plain flip" data-month="1" aria-label="Next month">${icon('back', 18)}</button>
      </div>
      <div class="cal-grid">${WD.map(w => `<span class="cal-wd">${w}</span>`).join('')}${cells.join('')}</div>
      <div class="cal-legend"><span><i class="mark plan"></i>Plans</span>${cal.connected ? '<span><i class="mark busy"></i>Busy</span>' : ''}<span><i class="swatch"></i>Trip</span></div>
    </section>

    <section>
      <div class="section-head"><h3>Trips</h3><a class="pill small dark" href="#/plans/new">+ Plan a trip</a></div>
      ${upcoming.length ? upcoming.map(tripCard).join('') : '<p class="muted">Going somewhere? Add the trip and Roam lines up ideas and events there for your dates.</p>'}
    </section>

    ${soon.length ? `<section><h3>Coming up</h3>${soon.slice(0, 12).map(({ d, it }) =>
      `<a class="agenda-item" href="#/plans/day/${d}"><span class="agenda-day">${esc(P.shortDay(d))}</span><span>${esc(it.name)}</span></a>`).join('')}</section>` : ''}

    <section class="card list">
      ${cal.connected
        ? `<div class="setting"><span><strong>Phone calendar</strong><small>Busy times show here and pause “heading out” ideas. Read only.</small></span><button type="button" class="pill outline small" data-cal="off">Turn off</button></div>`
        : `<div class="setting"><span><strong>See your busy times</strong><small>Connect your phone’s calendar (read only). Roam never changes it.</small></span><button type="button" class="pill dark small" data-cal="on">Connect</button></div>`}
    </section>`;

  root.onclick = e => {
    const m = e.target.closest('[data-month]');
    if (m) { const d = P.parse(month + '-01'); d.setMonth(d.getMonth() + Number(m.dataset.month)); month = P.ymd(d).slice(0, 7); app.rerender(); return; }
    const c = e.target.closest('[data-cal]');
    if (c) {
      if (c.dataset.cal === 'on') phone.request('calendar');
      else { phone.calendarOff?.(true); P.forgetCalendar(); toast('Calendar disconnected.'); app.rerender(); }
    }
  };
}

function tripCard(t) {
  const now = P.today();
  const n = P.range(t.start, t.end).reduce((sum, d) => sum + P.plannedOn(d).length, 0);
  const until = P.daysBetween(now, t.start);
  const when = t.start <= now ? 'Happening now' : until === 1 ? 'Tomorrow' : `In ${until} days`;
  const cover = data.places().filter(p => p.photos?.length && miles(t.place, p) < 15).sort((a, b) => (b.ratings || 0) - (a.ratings || 0))[0];
  return `<a class="trip-card" href="#/plans/trip/${encodeURIComponent(t.id)}">
    <span class="ph trip-cover t-sky">${cover ? `<img src="${esc(photoSrc(cover, 400))}" alt="" loading="lazy" data-fade>` : ''}</span>
    <span class="trip-text"><span class="eyebrow">${esc(when.toUpperCase())}</span><strong>${esc(t.name)}</strong>
    <span class="meta">${esc(P.tripDates(t))} · ${n ? `${n} planned` : 'nothing planned yet'}</span></span>
  </a>`;
}

// ---- One day ----------------------------------------------------------------------------------

function day(root, date, app) {
  const t = P.tripOn(date);
  if (t) loadTrip(t, app);
  const origin = t ? t.place : app.origin();
  const entries = P.calendarOn(date);
  const planned = P.plannedOn(date).map(data.get).filter(Boolean);
  const dayStart = P.parse(date).getTime();
  const events = data.events().filter(e => available(e) && e.start >= dayStart && e.start < dayStart + 86400000
    && origin && miles(origin, e) < 40 && !planned.includes(e)).sort((a, b) => a.start - b.start).slice(0, 6);
  const ahead = P.daysBetween(P.today(), date);
  const ideas = origin && ahead >= 0 && ahead <= 21
    ? diversify(data.places().filter(p => available(p) && miles(origin, p) < 25 && !P.plannedOn(date).includes(p.id)), p => score(p, null, origin), 5)
    : [];
  const label = P.shortDay(date);

  root.innerHTML = `<header class="sub-head">
      <a class="round" href="#/plans" aria-label="Back to Plans">${icon('back', 20)}</a>
      <h1>${esc(P.longDay(date))}</h1>
    </header>
    ${t ? `<a class="chip trip-chip" href="#/plans/trip/${encodeURIComponent(t.id)}">${icon('pin', 14)}${esc(t.name)}</a>` : ''}
    ${entries.length ? `<section><h3>On your calendar</h3>${entries.map(r => `<div class="cal-entry ${r.free ? 'free' : ''}">
        <span class="cal-time">${r.allDay ? 'All day' : `${timeLabel(new Date(r.start))} – ${timeLabel(new Date(r.end))}`}</span><span>${esc(r.title)}</span></div>`).join('')}</section>` : ''}
    <section><h3>Your plans</h3>
      ${planned.length ? planned.map(p => plannedRow(p, origin, date)).join('') : '<p class="muted">Nothing planned yet. Add something below, or tap “Plan it” on any place.</p>'}
    </section>
    ${events.length ? `<section><h3>Happening ${label === 'Today' ? 'today' : 'that day'}</h3>${events.map(e => planRow(e, origin, date, 'Add')).join('')}</section>` : ''}
    ${ideas.length ? `<section><h3>Ideas${t ? ` in ${esc(t.place.name)}` : ''}</h3>${ideas.map(p => planRow(p, origin, date, label)).join('')}</section>` : ''}`;
  root.onclick = e => { act(e, app); };
  watchImages(root);
}

// ---- A trip -----------------------------------------------------------------------------------

function trip(root, id, app) {
  const t = P.tripById(id);
  if (!t) { root.innerHTML = '<section class="empty-hero"><h1>Trip not found</h1><a class="pill dark" href="#/plans">Back to Plans</a></section>'; return; }
  const days = P.range(t.start, t.end);
  const sel = days.includes(tripDay[t.id]) ? tripDay[t.id] : days.find(d => d >= P.today()) || days[0];
  tripDay[t.id] = sel;
  loadTrip(t, app);

  const planned = P.plannedOn(sel).map(data.get).filter(Boolean);
  const entries = P.calendarOn(sel);
  const start = P.parse(t.start).getTime(), end = P.parse(t.end).getTime() + 86400000;
  const events = data.events().filter(e => available(e) && e.start >= start && e.start < end && miles(t.place, e) < 40)
    .sort((a, b) => score(b, null, t.place) - score(a, null, t.place)).slice(0, 10);
  const ideas = diversify(data.places().filter(p => available(p) && miles(t.place, p) < 25), p => score(p, null, t.place), 12);
  const busy = loadingTrips.has(t.id);

  root.innerHTML = `<header class="sub-head">
      <a class="round" href="#/plans" aria-label="Back to Plans">${icon('back', 20)}</a>
      <h1>${esc(t.name)}</h1>
    </header>
    <p class="trip-meta">${icon('pin', 16)}${esc(t.place.address || t.place.name)} · ${esc(P.tripDates(t))}</p>
    <div class="filters days">${days.map(d => `<button type="button" class="filter ${d === sel ? 'on' : ''}" data-day="${d}">${esc(P.shortDay(d))}${P.plannedOn(d).length ? ` · ${P.plannedOn(d).length}` : ''}</button>`).join('')}</div>
    ${entries.length ? `<section><h3>On your calendar</h3>${entries.map(r => `<div class="cal-entry"><span class="cal-time">${r.allDay ? 'All day' : timeLabel(new Date(r.start))}</span><span>${esc(r.title)}</span></div>`).join('')}</section>` : ''}
    <section><h3>${esc(P.longDay(sel))}</h3>
      ${planned.length ? planned.map(p => plannedRow(p, t.place, sel)).join('') : '<p class="muted">Nothing planned for this day yet.</p>'}
    </section>
    ${events.length ? `<section><h3>Events during your trip</h3>${events.map(e => planRow(e, t.place, P.ymd(new Date(e.start)), P.shortDay(P.ymd(new Date(e.start))))).join('')}</section>` : ''}
    <section><h3>Ideas in ${esc(t.place.name)}</h3>
      ${ideas.length ? ideas.map(p => planRow(p, t.place, sel, P.shortDay(sel))).join('') : busy ? '<p class="muted">Looking around…</p>' : '<p class="muted">No ideas loaded yet. Check your connection.</p>'}
    </section>
    <div class="trip-actions">
      <a class="pill outline small" href="#/plans/edit/${encodeURIComponent(t.id)}">Edit trip</a>
      <button type="button" class="pill outline small" data-delete>Delete trip</button>
    </div>`;

  root.onclick = e => {
    if (act(e, app)) return;
    const d = e.target.closest('[data-day]');
    if (d) { tripDay[t.id] = d.dataset.day; app.rerender(); return; }
    const del = e.target.closest('[data-delete]');
    if (del) {
      if (!del.dataset.confirm) { del.dataset.confirm = '1'; del.textContent = 'Tap again to delete'; return; }
      P.deleteTrip(t.id);
      toast('Trip deleted.');
      app.go('#/plans');
    }
  };
  watchImages(root);
}

/** Fetch ideas and events for a trip once per app session (the phone caches them too). */
function loadTrip(t, app) {
  const key = `${t.id}|${t.start}|${t.end}|${t.place.lat}`;
  if (loadTrip.done?.has(key) || loadingTrips.has(t.id)) return;
  (loadTrip.done ||= new Set()).add(key);
  loadingTrips.add(t.id);
  Promise.allSettled([data.loadPlacesFor(t.place), data.loadEventsBetween(t.place, t.start, t.end)])
    .then(() => { loadingTrips.delete(t.id); if (location.hash.startsWith('#/plans/')) app.rerender(); });
}

// ---- New / edit trip --------------------------------------------------------------------------

function form(root, id, app) {
  const existing = id ? P.tripById(id) : null;
  if (!draft || draft.id !== (existing?.id || 'new')) {
    draft = existing ? { ...existing, place: { ...existing.place } } : { id: 'new', name: '', place: null, start: P.addDays(P.today(), 7), end: P.addDays(P.today(), 9) };
    found = [];
  }
  root.innerHTML = `<header class="sub-head">
      <a class="round" href="${existing ? `#/plans/trip/${encodeURIComponent(existing.id)}` : '#/plans'}" aria-label="Cancel">${icon('back', 20)}</a>
      <h1>${existing ? 'Edit trip' : 'Plan a trip'}</h1>
    </header>
    <form class="trip-form" data-trip>
      <label class="form-label">Where to?</label>
      ${draft.place ? `<div class="here">${icon('pin', 18)}<span><strong>${esc(draft.place.address || draft.place.name)}</strong><button type="button" class="text-link" data-change>Change</button></span></div>`
        : `<div class="search">${icon('search', 20)}<input type="search" name="where" placeholder="A city, town or address" aria-label="Destination" enterkeyhint="search" autocomplete="off"></div>
           <div class="results">${found.map((p, i) => `<button type="button" class="result" data-pick="${i}">${icon('pin', 18)}<span>${esc(p.name)}</span></button>`).join('')}</div>`}
      <div class="dates">
        <label>From<input type="date" name="start" value="${draft.start}" min="${P.today()}" required></label>
        <label>To<input type="date" name="end" value="${draft.end}" min="${P.today()}" required></label>
      </div>
      <label class="form-label" for="trip-name">Name</label>
      <input id="trip-name" class="text-input" name="name" value="${esc(draft.name)}" placeholder="${esc(draft.place ? `Trip to ${draft.place.name}` : 'Trip name')}" maxlength="60">
      <button class="pill dark wide" type="submit">${existing ? 'Save changes' : 'Create trip'}${icon('arrow', 18)}</button>
    </form>`;

  const formEl = $('[data-trip]', root);
  const keep = () => {
    const f = new FormData(formEl);
    draft.start = f.get('start') || draft.start;
    draft.end = f.get('end') || draft.end;
    draft.name = f.get('name') ?? draft.name;
  };
  root.onclick = e => {
    const pick = e.target.closest('[data-pick]');
    if (pick) {
      keep();
      const p = found[Number(pick.dataset.pick)];
      draft.place = { name: p.town || p.name.split(',')[0], address: p.name, lat: p.lat, lng: p.lng };
      found = [];
      app.rerender();
      return;
    }
    if (e.target.closest('[data-change]')) { keep(); draft.place = null; app.rerender(); }
  };
  formEl.onkeydown = async e => {
    if (e.key !== 'Enter' || e.target.name !== 'where') return;
    e.preventDefault();
    const q = e.target.value.trim();
    if (q.length < 3) return;
    keep();
    try {
      found = await data.geocode(q);
      if (!found.length) toast('No places found for that.');
    } catch (err) { toast(err.message); }
    app.rerender();
  };
  formEl.onsubmit = e => {
    e.preventDefault();
    keep();
    if (!draft.place) { toast('Choose where you’re going first.'); return; }
    if (!draft.start || !draft.end || draft.end < draft.start) { toast('The trip has to end on or after it starts.'); return; }
    if (P.daysBetween(draft.start, draft.end) > 30) { toast('Trips can be up to a month long.'); return; }
    const t = { ...draft, id: existing?.id || 't' + Date.now().toString(36), name: draft.name.trim() || `Trip to ${draft.place.name}`, createdAt: existing?.createdAt || Date.now() };
    P.saveTrip(t);
    draft = null;
    app.go('#/plans/trip/' + encodeURIComponent(t.id));
  };
}
