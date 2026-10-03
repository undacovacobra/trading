// Places, events and weather around you. The phone side caches these; this keeps the latest
// results in memory (and a small copy on disk) so the app opens instantly with what it had.

import { state } from './store.js';

const CACHE_KEY = 'roam2-cache';
const catalog = new Map();   // id -> place or event
let lastPlaces = { at: 0, origin: null, source: null, notice: null };
let lastEvents = { at: 0, needsKey: false, error: null };
let weather = null;

try {
  const c = JSON.parse(localStorage.getItem(CACHE_KEY) || '{}');
  for (const p of c.items || []) catalog.set(p.id, p);
  weather = c.weather || null;
  lastPlaces = c.lastPlaces || lastPlaces;
} catch { /* empty start */ }

function persist() {
  try {
    const items = [...catalog.values()].filter(i => i.type !== 'event' || i.start > Date.now()).slice(-400);
    localStorage.setItem(CACHE_KEY, JSON.stringify({ items, weather, lastPlaces }));
  } catch { /* storage full; memory still works */ }
}

async function getJSON(url, timeout = 30000) {
  const r = await fetch(url, { signal: AbortSignal.timeout ? AbortSignal.timeout(timeout) : undefined });
  const data = await r.json().catch(() => ({}));
  if (!r.ok || data.error) throw new Error(data.error || 'Unavailable right now.');
  return data;
}

const q = o => `lat=${o.lat.toFixed(5)}&lng=${o.lng.toFixed(5)}`;

export async function loadPlaces(origin, force = false) {
  const data = await getJSON(`/api/places?${q(origin)}${force ? '&force=1' : ''}`, 60000);
  for (const p of data.places || []) catalog.set(p.id, { ...catalog.get(p.id), ...p });
  lastPlaces = { at: data.fetchedAt || Date.now(), origin, source: data.source, notice: data.notice || null };
  persist();
  return data;
}

/** Places around somewhere else (a trip), without touching what Today is showing. */
export async function loadPlacesFor(origin) {
  const data = await getJSON(`/api/places?${q(origin)}`, 60000);
  for (const p of data.places || []) catalog.set(p.id, { ...catalog.get(p.id), ...p });
  persist();
  return data;
}

/** Events near a place between two dates ("2026-11-02"), for trips. */
export async function loadEventsBetween(origin, from, to) {
  const data = await getJSON(`/api/events?${q(origin)}&from=${from}&to=${to}`);
  for (const e of data.events || []) catalog.set(e.id, e);
  persist();
  return data;
}

export async function loadEvents(origin) {
  try {
    const data = await getJSON(`/api/events?${q(origin)}`);
    for (const e of data.events || []) catalog.set(e.id, e);
    lastEvents = { at: Date.now(), needsKey: !!data.needsKey, error: null };
    persist();
  } catch (e) {
    lastEvents = { ...lastEvents, error: e.message };
  }
  return lastEvents;
}

export async function loadWeather(origin) {
  try {
    weather = await getJSON(`/api/weather?${q(origin)}`);
    persist();
  } catch { /* keep the last reading */ }
  return weather;
}

export async function search(text, origin) {
  const data = await getJSON(`/api/search?q=${encodeURIComponent(text)}&${q(origin)}`);
  for (const p of data.places || []) catalog.set(p.id, { ...catalog.get(p.id), ...p });
  return data.places || [];
}

export const geocode = text => getJSON(`/api/geocode?q=${encodeURIComponent(text)}`).then(d => d.results || []);
/** { name: 'Boulder', address: '1770 13th Street, Boulder' }, or null when the lookup fails. */
export const reverse = o => getJSON(`/api/reverse?${q(o)}`).then(d => (d.name || d.address ? d : null)).catch(() => null);

/** Keep an item the phone handed us (today's pick) so its page works. */
export function remember(item) {
  if (!item?.id) return;
  catalog.set(item.id, { ...catalog.get(item.id), ...item });
  persist();
}

export function get(id) {
  return catalog.get(id) || state.saved[id] || state.keep?.[id] || null;
}

export function all() {
  return [...catalog.values()];
}

export const places = () => all().filter(i => i.type !== 'event');
export const events = () => all().filter(i => i.type === 'event' && i.start > Date.now() - 3600000);
export const placesInfo = () => lastPlaces;
export const eventsInfo = () => lastEvents;
export const currentWeather = () => weather;
