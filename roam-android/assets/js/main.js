// Roam: routing, where you are, refreshing data, and talking to the phone.

import { state, save, onSave } from './store.js';
import { phone, status } from './native.js';
import { $, $$, hydrateIcons, toast, miles, debounce } from './util.js';
import * as data from './data.js';
import { context } from './context.js';
import { decay, pass, toggleSave, TAGS, evidence as evidenceOf } from './taste.js';
import * as today from './views/today.js';
import * as explore from './views/explore.js';
import * as saved from './views/saved.js';
import * as you from './views/you.js';
import * as place from './views/place.js';
import * as welcome from './views/welcome.js';
import * as plansView from './views/plans.js';
import { busySoon } from './plans.js';

window.toast = toast;

const views = { today, explore, plans: plansView, saved, you, place, welcome };
const scrolls = {};
let current = null;
let loading = false;
const shownNotices = new Set();

export const app = {
  origin: () => state.location,
  ctx: () => context(data.currentWeather()),
  isLoading: () => loading,
  go: hash => { if (location.hash !== hash) location.hash = hash; else route(); },
  rerender: () => route(true),
  refresh,
  locate,
  setLocation,
  pushSnapshotNow: () => pushSnapshotNow(),
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
  if (name === 'place' || ((name === 'explore' || name === 'plans') && parse().param)) { history.back(); return true; }
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
  // Name live locations in words: the town for the header, the street for "you're here".
  if (loc.mode === 'live' && (!loc.address || !prev || miles(prev, loc) > 0.1)) {
    data.reverse(loc).then(place => {
      if (!place || state.location?.lat !== loc.lat) return;
      state.location.name = place.name || state.location.name;
      state.location.address = place.address || '';
      save();
      route(true);
    });
  }
  // Only look for places again after a real move or when results are stale; the phone caches
  // anyway, but there's no reason to even ask on every location update.
  const moved = !prev || miles(prev, loc) > 1.5;
  const stale = Date.now() - data.placesInfo().at > 30 * 60000;
  if (reload && (moved || stale)) refresh(false, moved);
}

window.roamNativeFix = fix => {
  if (fix.error) {
    if (!state.location) toast(fix.error);
    route(true);
    return;
  }
  if (state.location?.mode === 'manual') return;
  const near = state.location && miles(state.location, fix) < 0.1;
  setLocation({ lat: fix.lat, lng: fix.lng, name: state.location && miles(state.location, fix) < 1.5 ? state.location.name : 'Near you', address: near ? state.location.address : '', mode: 'live' });
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
      data.loadPlaces(origin, force).catch(e => { if (!shownNotices.has(e.message)) { shownNotices.add(e.message); toast(e.message); } }),
      data.loadEvents(origin),
    ]);
  } finally {
    loading = false;
    route(true);
    pushSnapshot();
  }
  const notice = data.placesInfo().notice;
  if (notice && !shownNotices.has(notice)) { shownNotices.add(notice); toast(notice); }
}

// ---- Phone: replies to notifications and background suggestions ------------------------------

window.roamNativeRefresh = () => {
  const s = status();
  let changed = false;
  for (const n of s.nudged || []) if (n.item && !data.get(n.id)) data.remember(n.item);
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

window.roamNativeCalendarReady = () => { toast('Calendar connected.'); route(true); pushSnapshot(); };
window.roamNativeOpen = id => {
  // A "Heading out?" idea may be a place the app hasn't loaded; the phone keeps a copy.
  if (id && !data.get(id)) { const n = (status().nudged || []).find(x => x.id === id); if (n?.item) data.remember(n.item); }
  if (id && data.get(id)) app.go('#/place/' + encodeURIComponent(id));
};
window.roamNativeView = view => { if (view === 'pick') { app.go('#/today'); window.scrollTo(0, 0); } };
window.roamNativeShare = text => { app.go('#/explore'); setTimeout(() => explore.searchFor?.(text, app), 50); };

/** What the phone needs to choose the daily pick and send suggestions while Roam is closed. */
function buildSnapshot() {
  const origin = state.location;
  const now = Date.now();
  const exclude = new Set(Object.keys(state.hidden));
  for (const [id, at] of Object.entries(state.went)) if (now - at < 60 * 86400000) exclude.add(id);
  for (const [id, until] of Object.entries(state.snoozed)) if (until > now) exclude.add(id);
  const evidence = {};
  for (const tag of Object.keys(TAGS)) {
    const e = evidenceOf(tag);
    if (e.save || e.went) evidence[tag] = e;
  }
  return {
    daily: state.settings.daily,
    departure: { enabled: state.settings.departure },
    quiet: state.settings.quiet,
    location: origin ? { lat: origin.lat, lng: origin.lng } : null,
    weights: state.taste.weights,
    distance: state.taste.distance,
    exclude: [...exclude].slice(-500),
    evidence,
    max: 2, busy: busySoon(),
  };
}

function pushSnapshotNow() {
  phone.snapshot(JSON.stringify(buildSnapshot()));
}

const pushSnapshot = debounce(pushSnapshotNow, 1500);

onSave(pushSnapshot);

// ---- Start ----------------------------------------------------------------------------------

decay();
route();
if (state.onboarded) {
  if (!state.location || state.location.mode !== 'manual') locate();
  else if (Date.now() - data.placesInfo().at > 30 * 60000) refresh(false, false);
  pushSnapshot();
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible' || !state.onboarded) return;
  if (state.location?.mode !== 'manual') locate();
  else if (Date.now() - data.placesInfo().at > 30 * 60000) refresh(false, false);
});
hydrateIcons($('#tabs'));
