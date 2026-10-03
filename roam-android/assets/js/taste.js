// How Roam learns what you like, and how it scores a place or event for you right now.

import { state, save, record } from './store.js';
import { miles, timeLabel } from './util.js';

export const TAGS = {
  coffee:   { label: 'Coffee & tea',      plural: 'coffee spots',   line: n => `a slow coffee at ${n}` },
  quiet:    { label: 'Quiet corners',     plural: 'quiet places',   line: n => `a quiet hour at ${n}` },
  sweet:    { label: 'Something sweet',   plural: 'bakeries',       line: n => `something sweet at ${n}` },
  food:     { label: 'Good food',         plural: 'places to eat',  line: n => `dinner at ${n}` },
  drinks:   { label: 'Drinks',            plural: 'bars',           line: n => `drinks at ${n}` },
  nightlife:{ label: 'Nights out',        plural: 'night spots',    line: n => `a night out at ${n}` },
  outdoors: { label: 'Fresh air',         plural: 'outdoor spots',  line: n => `fresh air at ${n}` },
  trails:   { label: 'Easy trails',       plural: 'trails',         line: n => `a walk at ${n}` },
  views:    { label: 'Big views',         plural: 'viewpoints',     line: n => `the view from ${n}` },
  gardens:  { label: 'Gardens',           plural: 'gardens',        line: n => `a wander through ${n}` },
  culture:  { label: 'Art & culture',     plural: 'museums & galleries', line: n => `an afternoon at ${n}` },
  books:    { label: 'Bookshops',         plural: 'bookshops',      line: n => `browsing at ${n}` },
  sights:   { label: 'Local sights',      plural: 'sights',         line: n => `a look at ${n}` },
  fun:      { label: 'Fun & games',       plural: 'fun spots',      line: n => `some fun at ${n}` },
  music:    { label: 'Live music',        plural: 'live shows',     line: n => n },
  sports:   { label: 'Games & sports',    plural: 'games',          line: n => n },
  wellness: { label: 'Unwind',            plural: 'spas',           line: n => `a reset at ${n}` },
  active:   { label: 'Get moving',        plural: 'active spots',   line: n => `a workout at ${n}` },
};

const OUTDOOR = ['outdoors', 'trails', 'views', 'gardens'];
const DAY = 86400000;
const HALF_LIFE_DAYS = 45;

// ---- Learning -------------------------------------------------------------------------------

function nudge(tags, amount) {
  const w = state.taste.weights;
  for (const t of tags || []) w[t] = Math.max(-8, Math.min(8, (w[t] || 0) + amount));
}

/** Old habits fade: weights halve every 45 days so Roam keeps up with you. */
export function decay(now = Date.now()) {
  const days = (now - (state.taste.decayedAt || now)) / DAY;
  if (days < 1) return;
  const f = 0.5 ** (days / HALF_LIFE_DAYS);
  for (const k of Object.keys(state.taste.weights)) state.taste.weights[k] *= f;
  state.taste.decayedAt = now;
  save();
}

export function seed(tags) {
  nudge(tags, 2);
  state.onboarded = true;
  record('seed', { tags });
}

export function opened(item) {
  const today = new Date().toDateString();
  for (let i = state.history.length - 1; i >= 0; i--) {
    const h = state.history[i];
    if (h.type === 'open' && h.id === item.id) {
      if (new Date(h.at).toDateString() === today) return;
      break;
    }
  }
  nudge(item.tags, 0.3);
  record('open', item);
}

export function toggleSave(item) {
  if (state.saved[item.id]) {
    delete state.saved[item.id];
    nudge(item.tags, -1);
    record('unsave', item);
    return false;
  }
  state.saved[item.id] = { ...item, savedAt: Date.now() };
  nudge(item.tags, 2);
  record('save', item);
  return true;
}

export function went(item) {
  if (state.went[item.id]) {
    delete state.went[item.id];
    nudge(item.tags, -2);
    record('unwent', item);
    return false;
  }
  state.went[item.id] = Date.now();
  state.keep[item.id] = { ...item };
  nudge(item.tags, 3);
  record('went', item);
  return true;
}

/** "Not for me" with a reason; each teaches something different. */
export function pass(item, reason) {
  const now = Date.now();
  if (reason === 'vibe') { nudge(item.tags, -2.5); state.hidden[item.id] = { reason, at: now }; }
  if (reason === 'far') { state.taste.distance = Math.max(1, state.taste.distance * 0.8); state.hidden[item.id] = { reason, at: now }; }
  if (reason === 'been') { state.went[item.id] = state.went[item.id] || now; state.keep[item.id] = { ...item }; nudge(item.tags, 0.5); state.hidden[item.id] = { reason, at: now }; }
  if (reason === 'later') state.snoozed[item.id] = now + DAY;
  record('pass-' + reason, item);
}

export function bringBack(tag) {
  state.taste.weights[tag] = 0;
  for (const [id, h] of Object.entries(state.hidden)) if (h.reason === 'vibe') delete state.hidden[id];
  record('bring-back', { tags: [tag] });
}

export function shown(item) {
  const s = state.seen[item.id] || { n: 0, at: 0 };
  if (Date.now() - s.at < 3 * 3600000) return;
  state.seen[item.id] = { n: s.n + 1, at: Date.now() };
  save();
}

/** What you did with places carrying this tag: { save, went, pass }. */
export function evidence(tag) {
  const e = { save: 0, went: 0, pass: 0 };
  for (const h of state.history) {
    if (!h.tags?.includes(tag)) continue;
    if (h.type === 'save') e.save++;
    else if (h.type === 'went') e.went++;
    else if (h.type === 'pass-vibe') e.pass++;
  }
  return e;
}

// ---- Opening hours (Google periods: day 0 = Sunday, place-local time) ------------------------

export function openState(place, now = new Date()) {
  const periods = place?.hours?.periods;
  if (!Array.isArray(periods) || !periods.length) return { known: false };
  if (periods.length === 1 && !periods[0].close) return { known: true, open: true, label: 'Open 24 hours' };
  const WEEK = 10080, mins = now.getDay() * 1440 + now.getHours() * 60 + now.getMinutes();
  const at = p => p.day * 1440 + (p.hour || 0) * 60 + (p.minute || 0);
  let nextOpen = null;
  for (const p of periods) {
    if (!p.open || !p.close) continue;
    let o = at(p.open), c = at(p.close);
    if (c <= o) c += WEEK;
    for (const shift of [0, -WEEK]) {
      if (mins >= o + shift && mins < c + shift) {
        const left = c + shift - mins;
        const close = new Date(now.getTime() + left * 60000);
        return { known: true, open: true, closesIn: left, label: left <= 45 ? `Closes soon · ${timeLabel(close)}` : `Open until ${timeLabel(close)}` };
      }
    }
    const wait = (o - mins + WEEK) % WEEK;
    if (nextOpen === null || wait < nextOpen) nextOpen = wait;
  }
  if (nextOpen === null) return { known: false };
  const opens = new Date(now.getTime() + nextOpen * 60000);
  const sameDay = opens.toDateString() === now.toDateString();
  const day = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][opens.getDay()];
  return { known: true, open: false, opensIn: nextOpen, label: `Closed · opens ${sameDay ? '' : day + ' '}${timeLabel(opens)}` };
}

// ---- Scoring --------------------------------------------------------------------------------

export function tasteOf(tags) {
  if (!tags?.length) return 0;
  const w = state.taste.weights;
  let sum = 0;
  for (const t of tags) sum += Math.max(-6, Math.min(6, w[t] || 0));
  return sum / Math.sqrt(tags.length);
}

export function quality(p) {
  if (!p.rating) return 0;
  return (p.rating - 4.2) * 2.5 + Math.min(Math.log10((p.ratings || 0) + 1), 4) * 0.5;
}

export function available(item, now = Date.now()) {
  if (state.hidden[item.id]) return false;
  if ((state.snoozed[item.id] || 0) > now) return false;
  if (item.type === 'event' && item.start < now - 3600000) return false;
  return true;
}

/** How good this is for you, here, now. ctx comes from context.js. */
export function score(item, ctx, origin) {
  const d = origin ? miles(origin, item) : 0;
  const outdoor = item.tags?.some(t => OUTDOOR.includes(t));
  const tol = state.taste.distance * (outdoor ? 3 : 1) * (item.type === 'event' ? 4 : 1);
  let s = tasteOf(item.tags) - 1.6 * d / tol;
  if (item.type === 'event') {
    const days = (item.start - Date.now()) / DAY;
    s += 1.5 + (days < 3 ? 2 : days < 7 ? 1 : 0);
  } else {
    s += quality(item);
    if (item.photos?.length) s += 0.6;
    if (ctx) {
      let boost = 0;
      for (const t of item.tags || []) boost += ctx.boosts[t] || 0;
      s += Math.max(-3, Math.min(2.5, boost));
      const open = openState(item, ctx.date);
      if (open.known && !open.open) s -= open.opensIn <= 30 ? 0.5 : 3;
      if (open.closesIn <= 45) s -= 1.5;
    }
  }
  const seen = state.seen[item.id];
  if (seen && !state.saved[item.id]) s -= 0.6 * Math.min(seen.n, 4);
  if (state.went[item.id] && Date.now() - state.went[item.id] < 14 * DAY) s -= 3;
  return s;
}

/** Best-first, without five cafés in a row. */
export function diversify(items, scoreOf, count) {
  const pool = items.map(i => ({ i, s: scoreOf(i) })).sort((a, b) => b.s - a.s);
  const out = [], used = {};
  while (out.length < count && pool.length) {
    let best = 0, bestScore = -Infinity;
    for (let k = 0; k < Math.min(pool.length, 30); k++) {
      const main = pool[k].i.tags?.[0];
      const adj = pool[k].s - 1.2 * (used[main] || 0);
      if (adj > bestScore) { bestScore = adj; best = k; }
    }
    const [pick] = pool.splice(best, 1);
    out.push(pick.i);
    const main = pick.i.tags?.[0];
    used[main] = (used[main] || 0) + 1;
  }
  return out;
}

// ---- Why ------------------------------------------------------------------------------------

function topTag(item) {
  let best = null, bw = 0.9;
  for (const t of item.tags || []) {
    const w = state.taste.weights[t] || 0;
    if (w > bw) { bw = w; best = t; }
  }
  return best;
}

/** One short line for a card: why this, for you, now. */
export function reason(item, origin, ctx) {
  const t = topTag(item);
  if (t) {
    const e = evidence(t);
    if (e.went >= 2) return `You keep going to ${TAGS[t].plural}`;
    if (e.save >= 2) return `You've saved ${e.save} ${TAGS[t].plural}`;
  }
  if (item.type === 'event' && item.moreDates) return `${item.moreDates + 1} dates, pick one`;
  if (ctx?.sunsetSoon && item.tags?.includes('views')) return 'Catch the sunset from here';
  if (t) return `Because you like ${TAGS[t].label.toLowerCase()}`;
  if (item.rating >= 4.7 && item.ratings >= 300) return `A local favorite, ${compact(item.ratings)} reviews`;
  const learning = Object.values(state.taste.weights).some(w => Math.abs(w) > 0.5);
  if (learning && !state.history.some(h => h.tags?.some(x => item.tags?.includes(x)))) return 'Something new to try';
  return '';
}

/** A few sentences for the place page, built only from things Roam actually knows. */
export function reasonLong(item) {
  const parts = [];
  const t = topTag(item);
  if (t) {
    const e = evidence(t);
    const did = [e.save && `saved ${e.save}`, e.went && `been to ${e.went}`].filter(Boolean).join(' and ');
    const loved = item.rating >= 4.5 && item.ratings >= 100;
    parts.push(did
      ? `You've ${did} ${TAGS[t].plural}${loved ? ', and this is one of the best-loved nearby' : ', and this one has the same feel'}.`
      : `It matches what you told Roam you like: ${TAGS[t].label.toLowerCase()}.`);
  } else {
    parts.push('Something a little different from your usual, picked to keep things interesting.');
  }
  if (item.rating && item.ratings) parts.push(`Rated ${item.rating.toFixed(1)} by ${compact(item.ratings)} people on Google.`);
  const open = openState(item);
  if (open.known) parts.push(`${open.label}.`);
  const learned = Object.values(state.saved).filter(s => s.id !== item.id && s.tags?.some(x => item.tags?.includes(x))).slice(0, 3);
  return { text: parts.join(' '), from: learned.map(s => s.name) };
}

export function compact(n) {
  return n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k` : String(n);
}

export function lineFor(item) {
  const t = item.tags?.[0];
  if (item.type === 'event') return item.venue ? `${item.name} at ${item.venue}` : item.name;
  return TAGS[t] ? TAGS[t].line(item.name) : item.name;
}
