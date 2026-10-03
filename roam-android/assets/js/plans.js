// Trips, things you've planned for a day, and your phone calendar (read only).

import { state, save, record } from './store.js';
import { phone, status } from './native.js';
import { miles } from './util.js';

const DAY = 86400000;
if (!state.trips) state.trips = [];
if (!state.plans) state.plans = {};

// ---- Dates (always local calendar days, "2026-10-10") -----------------------------------------

export const ymd = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
export const parse = s => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
export const today = () => ymd(new Date());
export const addDays = (s, n) => { const d = parse(s); d.setDate(d.getDate() + n); return ymd(d); };
export const daysBetween = (a, b) => Math.round((parse(b) - parse(a)) / DAY);

export function range(from, to) {
  const out = [];
  for (let d = from; d <= to && out.length < 62; d = addDays(d, 1)) out.push(d);
  return out;
}

const WD = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Today", "Tomorrow", "Sat", or "Sat Oct 17" further out. */
export function shortDay(s) {
  const diff = daysBetween(today(), s);
  const d = parse(s);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff > 1 && diff < 7) return WD[d.getDay()];
  return `${WD[d.getDay()]} ${MON[d.getMonth()]} ${d.getDate()}`;
}

export function longDay(s) {
  return parse(s).toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' });
}

export function tripDates(t) {
  const a = parse(t.start), b = parse(t.end);
  const same = a.getMonth() === b.getMonth();
  return t.start === t.end ? `${MON[a.getMonth()]} ${a.getDate()}`
    : `${MON[a.getMonth()]} ${a.getDate()} – ${same ? '' : MON[b.getMonth()] + ' '}${b.getDate()}`;
}

// ---- Trips ------------------------------------------------------------------------------------

export function trips() {
  return [...state.trips].sort((a, b) => a.start.localeCompare(b.start));
}

export const upcomingTrips = () => trips().filter(t => t.end >= today());
export const tripOn = date => state.trips.find(t => t.start <= date && t.end >= date) || null;
export const tripById = id => state.trips.find(t => t.id === id) || null;

/** A trip whose destination is near this place (for "plan it" on the right days). */
export function tripNear(item) {
  const here = state.location;
  return upcomingTrips().find(t => {
    const d = miles(t.place, item);
    return d < 12 && (!here || d < miles(here, item));
  }) || null;
}

export function saveTrip(trip) {
  const i = state.trips.findIndex(t => t.id === trip.id);
  if (i >= 0) state.trips[i] = trip; else state.trips.push(trip);
  record('trip', { id: trip.id });
  save();
}

export function deleteTrip(id) {
  state.trips = state.trips.filter(t => t.id !== id);
  save();
}

// ---- Plans for a day --------------------------------------------------------------------------

export function plannedOn(date) {
  return (state.plans[date] || []).filter(Boolean);
}

export function whenPlanned(id) {
  return Object.keys(state.plans).filter(d => state.plans[d].includes(id)).sort();
}

export function plan(date, item) {
  const list = state.plans[date] || (state.plans[date] = []);
  if (!list.includes(item.id)) list.push(item.id);
  state.keep[item.id] = { ...item };
  record('plan', item);
  save();
}

export function unplan(date, id) {
  state.plans[date] = (state.plans[date] || []).filter(x => x !== id);
  if (!state.plans[date].length) delete state.plans[date];
  save();
}

/** Drop plans for days long gone so storage stays small. */
export function tidy() {
  const cutoff = addDays(today(), -60);
  for (const d of Object.keys(state.plans)) if (d < cutoff) delete state.plans[d];
}

// ---- Phone calendar ---------------------------------------------------------------------------

let cache = { from: 0, to: 0, at: 0, records: [], status: null };

export function calendarStatus() {
  const s = status();
  return { connected: !!s.calendar, permission: !!s.calendarPermission, available: typeof phone.calendar === 'function' };
}

/** Calendar entries overlapping [from, to) (ms). Re-reads at most every 2 minutes. */
export function calendar(from, to) {
  if (!calendarStatus().connected) return [];
  const fresh = Date.now() - cache.at < 120000 && from >= cache.from && to <= cache.to;
  if (!fresh) {
    const a = Math.min(from, Date.now() - 7 * DAY), b = Math.max(to, Date.now() + 120 * DAY);
    try {
      const r = JSON.parse(phone.calendar(a, b));
      cache = { from: a, to: b, at: Date.now(), records: r.records || [], status: r.status };
    } catch {
      cache = { from: a, to: b, at: Date.now(), records: [], status: 'error' };
    }
  }
  return cache.records.filter(r => {
    const [s, e] = span(r);
    return s < to && e > from;
  });
}

/** An entry's start and end in local time (all-day entries cover whole local days). */
export function span(r) {
  if (r.allDay && r.dateStart) return [parse(r.dateStart).getTime(), parse(r.dateEnd || r.dateStart).getTime()];
  return [r.start, r.end];
}

export function calendarOn(date) {
  const a = parse(date).getTime();
  return calendar(a, a + DAY).sort((x, y) => span(x)[0] - span(y)[0]);
}

/** Busy blocks for the phone's notification rules (next two days, ignoring "free" entries). */
export function busySoon() {
  return calendar(Date.now(), Date.now() + 2 * DAY).filter(r => !r.free && !r.allDay)
    .map(r => ({ start: r.start, end: r.end })).slice(0, 200);
}

export function forgetCalendar() {
  cache = { from: 0, to: 0, at: 0, records: [], status: null };
}
