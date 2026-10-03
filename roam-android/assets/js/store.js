// Everything Roam remembers about you, kept in this phone's storage under one key.

const KEY = 'roam2';
const HISTORY_MAX = 600;

function fresh() {
  return {
    v: 2,
    onboarded: false,
    taste: { weights: {}, decayedAt: Date.now(), distance: 5 },
    saved: {},     // id -> snapshot of the place or event, so Saved works offline
    went: {},      // id -> time you said you went
    keep: {},      // id -> snapshot of places you went to, so they stay in Been
    hidden: {},    // id -> { reason, at }
    snoozed: {},   // id -> until
    seen: {},      // id -> { n: times shown up top, at }
    history: [],   // { at, type, id, tags }
    location: null, // { lat, lng, name, mode: 'live' | 'manual', at }
    settings: {
      daily: { enabled: true, time: '16:30' },
      departure: false,
      quiet: { start: '22:00', end: '08:00' },
    },
  };
}

function migrate() {
  // Roam 0.7–0.8 kept your interests, quiet hours and chosen place under other keys.
  const s = fresh();
  try {
    const old = JSON.parse(localStorage.getItem('roam-v1') || 'null');
    const companion = JSON.parse(localStorage.getItem('roam-companion-v2') || 'null');
    const map = { Coffee: 'coffee', 'Local food': 'food', Outdoors: 'outdoors', 'Scenic views': 'views', Culture: 'culture',
      Nightlife: 'nightlife', 'Live music': 'music', History: 'culture', 'Family activities': 'fun', Wellness: 'wellness',
      'Shopping & markets': 'sights', 'Hidden gems': 'sights', 'Fitness & sports': 'active', Adrenaline: 'active' };
    for (const interest of old?.profile?.interests || []) {
      const tag = map[interest];
      if (tag) s.taste.weights[tag] = (s.taste.weights[tag] || 0) + 1.5;
    }
    if (companion?.answers?.quiet?.start) s.settings.quiet = companion.answers.quiet;
    const place = JSON.parse(localStorage.getItem('roam-selected-location') || 'null');
    if (Number.isFinite(place?.lat)) s.location = { lat: place.lat, lng: place.lng, name: place.name, mode: 'manual', at: Date.now() };
    if (Object.keys(s.taste.weights).length) s.onboarded = true;
  } catch { /* start fresh */ }
  return s;
}

function load() {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const s = Object.assign(fresh(), JSON.parse(raw));
      // 0.9–0.10 had a weekly digest; 0.11 sends one pick a day instead.
      if (!s.settings.daily) s.settings.daily = { enabled: s.settings.weekly?.enabled !== false, time: '16:30' };
      delete s.settings.weekly;
      return s;
    }
  } catch { /* fall through */ }
  return migrate();
}

export const state = load();
const listeners = new Set();
let saveTimer;

export function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    if (state.history.length > HISTORY_MAX) state.history.splice(0, state.history.length - HISTORY_MAX);
    try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* storage full: keep going in memory */ }
    for (const fn of listeners) fn();
  }, 150);
}

export function onSave(fn) { listeners.add(fn); }

export function record(type, item) {
  state.history.push({ at: Date.now(), type, id: item?.id, tags: item?.tags || [] });
  save();
}

// ---- Backups --------------------------------------------------------------------------------

export function backupText() {
  return JSON.stringify({ app: 'roam', format: 2, exportedAt: new Date().toISOString(), data: { [KEY]: JSON.stringify(state) } });
}

/** Validates a backup file's text; returns the state to restore or throws a readable error. */
export function parseBackup(text) {
  let parsed;
  try { parsed = JSON.parse(text); } catch { throw new Error('That file isn’t a Roam backup.'); }
  if (parsed?.app !== 'roam' || typeof parsed.data !== 'object') throw new Error('That file isn’t a Roam backup.');
  if (typeof parsed.data[KEY] === 'string') return { when: parsed.exportedAt, apply: () => localStorage.setItem(KEY, parsed.data[KEY]) };
  // An older backup: restore its keys and let migrate() pick them up.
  const old = Object.entries(parsed.data).filter(([k, v]) => /^roam-/.test(k) && typeof v === 'string');
  if (!old.length) throw new Error('That backup is empty.');
  return { when: parsed.exportedAt, apply: () => { localStorage.removeItem(KEY); for (const [k, v] of old) localStorage.setItem(k, v); } };
}

export function resetTaste() {
  state.taste = fresh().taste;
  state.hidden = {};
  state.seen = {};
  state.history = state.history.filter(h => h.type === 'save' || h.type === 'went');
  save();
}
