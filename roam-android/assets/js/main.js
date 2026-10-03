// Roam: routing, where you are, refreshing data, and talking to the phone.

import { state, save, onSave } from './store.js';
import { phone, status } from './native.js';
import { $, $$, hydrateIcons, toast, miles, debounce } from './util.js';
import * as data from './data.js';
import { context } from './context.js';
import { decay, pass, toggleSave, score, available, lineFor, reason } from './taste.js';
import * as today from './views/today.js';
import * as explore from './views/explore.js';
import * as saved from './views/saved.js';
import * as you from './views/you.js';
import * as place from './views/place.js';
import * as welcome from './views/welcome.js';

window.toast = toast;

const views = { today, explore, saved, you, place, welcome };
const scrolls = {};
let current = null;
let loading = false;

export const app = {
  origin: () => state.location,
  ctx: () => context(data.currentWeather()),
  isLoading: () => loading,
  go: hash => { if (location.hash !== hash) location.hash = hash; else route(); },
  rerender: () => route(true),
  refresh,
  locate,
  setLocation,
};

// ---- Routing --------------------------------------------------------------------------------

function parse() {
  const [, name = 'today', ...rest] = (location.hash || '#/today').split('/');
  return { name: views[name] ? name : 'today', param: rest.length ? decodeURIComponent(rest.join('/')) : '' };
}

function route(keepScroll = false) {
  if (!state.onboarded && parse().name !== 'welcome') { location.replace('#/welcome'); return; }
  const { name, param } = parse();
  const key = name + '/' + param;
  if (current && !keepScroll) scrolls[current] = window.scrollY;
  const root = $('#view');
  root.dataset.view = name;
  views[name].render(root, param, app);
  hydrateIcons(root);
  const tabs = $('#tabs');
  tabs.hidden = name === 'welcome' || name === 'place';
  for (const a of $$('a', tabs)) a.classList.toggle('active', a.dataset.tab === name);
  if (!keepScroll) window.scrollTo(0, key !== current && name !== 'place' ? scrolls[key] || 0 : 0);
  if (!keepScroll) root.animate?.([{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'none' }], { duration: 180, easing: 'ease-out' });
  current = key;
}

window.addEventListener('hashchange', () => route());

/** Android's back button: close what's open, else go to Today, else leave the app. */
window.roamBack = () => {
  const { name } = parse();
  if (name === 'place' || (name === 'explore' && parse().param)) { history.back(); return true; }
  if (name !== 'today' && name !== 'welcome') { app.go('#/today'); return true; }
  return false;
};

// ---- Location -------------------------------------------------------------------------------

function locate() {
  phone.request('current');
}

async function setLocation(loc, { reload = true } = {}) {
  const prev = state.location;
  state.location = { ...loc, at: Date.now() };
  save();
  if (!loc.name || loc.name === 'Near you') {
    data.reverse(loc).then(name => {
      if (name && state.location?.lat === loc.lat) { state.location.name = name; save(); route(true); }
    });
  }
  const moved = !prev || miles(prev, loc) > 1.5;
  if (reload) refresh(false, moved);
}

window.roamNativeFix = fix => {
  if (fix.error) {
    if (!state.location) toast(fix.error);
    route(true);
    return;
  }
  if (state.location?.mode === 'manual') return;
  const near = state.location && miles(state.location, fix) < 1;
  setLocation({ lat: fix.lat, lng: fix.lng, name: near ? state.location.name : 'Near you', mode: 'live' });
};

// ---- Data -----------------------------------------------------------------------------------

async function refresh(force = false, rerender = true) {
  const origin = state.location;
  if (!origin || loading) return;
  loading = true;
  if (rerender) route(true);
  try {
    await data.loadWeather(origin);
    await Promise.all([
      data.loadPlaces(origin, force).catch(e => toast(e.message)),
      data.loadEvents(origin),
    ]);
  } finally {
    loading = false;
    route(true);
    pushSnapshot();
  }
  const notice = data.placesInfo().notice;
  if (notice) toast(notice);
}

// ---- Phone: replies to notifications and background suggestions ------------------------------

window.roamNativeRefresh = () => {
  const s = status();
  let changed = false;
  for (const r of s.replies || []) {
    const item = data.get(r.placeId);
    if (item) {
      if (r.feeling === 'yes' && !state.saved[item.id]) toggleSave(item);
      else if (r.feeling === 'later') pass(item, 'later');
      else if (r.feeling === 'no') pass(item, 'vibe');
      changed = true;
    }
    phone.ack(r.id);
  }
  if (changed) { save(); route(true); }
};

window.roamNativeOpen = id => { if (id && data.get(id)) app.go('#/place/' + encodeURIComponent(id)); };
window.roamNativeView = view => { if (view === 'picks') { app.go('#/today'); setTimeout(() => $('#picks')?.scrollIntoView({ behavior: 'smooth' }), 300); } };
window.roamNativeShare = text => { app.go('#/explore'); setTimeout(() => explore.searchFor?.(text, app), 50); };

/** What the phone needs to send good notifications while Roam is closed. */
const pushSnapshot = debounce(() => {
  const origin = state.location;
  if (!origin) return;
  const ctx = null; // background ranking ignores the current hour; the phone checks timing itself
  const items = data.all().filter(i => available(i) && miles(origin, i) < (i.type === 'event' ? 40 : 15));
  const ranked = items.map(i => ({ i, s: score(i, ctx, origin) })).sort((a, b) => b.s - a.s);
  const places = ranked.filter(r => r.i.type !== 'event').slice(0, 120).map(({ i, s }) => ({
    id: i.id, name: i.name, lat: i.lat, lng: i.lng, rank: Math.round(s * 10), reason: reason(i, origin) || 'A place you might enjoy',
    lateNight: i.tags.includes('nightlife'), start: 0, rejected: false, snoozeUntil: state.snoozed[i.id] || 0,
  }));
  const picks = ranked.slice(0, 40).map(({ i, s }) => ({
    id: i.id, name: i.name, type: i.type === 'event' ? 'event' : 'place', score: s, start: i.start || 0, tags: i.tags,
    photo: i.type === 'event' ? i.image || '' : i.photos?.[0]?.ref || '', line: lineFor(i),
  }));
  phone.snapshot(JSON.stringify({
    weekly: state.settings.weekly,
    departure: { enabled: state.settings.departure },
    quiet: state.settings.quiet,
    max: 1, busy: [], places, picks,
  }));
}, 1500);

onSave(pushSnapshot);

// ---- Start ----------------------------------------------------------------------------------

decay();
route();
if (state.onboarded) {
  if (!state.location || state.location.mode !== 'manual') locate();
  if (state.location && Date.now() - data.placesInfo().at > 30 * 60000) refresh(false, false);
  else pushSnapshot();
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible' || !state.onboarded) return;
  if (state.location?.mode !== 'manual') locate();
  if (Date.now() - data.placesInfo().at > 30 * 60000) refresh(false, false);
});
hydrateIcons($('#tabs'));
