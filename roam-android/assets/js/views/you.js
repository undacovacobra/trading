// You: what Roam has learned (and lets you correct), notifications, location, keys, your data.

import { state, save, backupText, parseBackup, resetTaste } from '../store.js';
import { phone, status, isPhone } from '../native.js';
import { $, esc, icon, toast } from '../util.js';
import * as data from '../data.js';
import { TAGS, evidence, bringBack } from '../taste.js';

const DAYS = [[1, 'Monday'], [2, 'Tuesday'], [3, 'Wednesday'], [4, 'Thursday'], [5, 'Friday'], [6, 'Saturday'], [7, 'Sunday']];
let places = [];

export function render(root, section, app) {
  if (section === 'location') return renderLocation(root, app);
  const s = status();
  const w = state.taste.weights;
  const tags = Object.keys(w).filter(t => TAGS[t]);
  const more = tags.filter(t => w[t] > 0.4).sort((a, b) => w[b] - w[a]).slice(0, 6);
  const less = tags.filter(t => w[t] < -0.4).sort((a, b) => w[a] - w[b]).slice(0, 4);
  const max = Math.max(1, ...more.map(t => w[t]));
  const keys = s.keys || {};
  const weekly = state.settings.weekly;

  root.innerHTML = `<h1 class="page-title">You, lately</h1>
    <p class="lede">Built from what you save, skip and go to. Tap anything to change it.</p>

    <section class="card">
      <h2 class="label">MORE OF THIS</h2>
      ${more.length ? more.map(t => {
        const e = evidence(t);
        const note = e.went ? `went to ${e.went}` : e.save ? `saved ${e.save}` : 'you said so';
        return `<div class="meter"><div class="meter-head"><strong>${esc(TAGS[t].label)}</strong><span>${note}</span></div>
          <div class="bar"><i style="width:${Math.round(100 * w[t] / max)}%"></i></div></div>`;
      }).join('') : '<p class="muted">Nothing yet. Save a few places and this fills in.</p>'}
    </section>

    ${less.length ? `<section class="card">
      <h2 class="label">SHOWING YOU LESS</h2>
      ${less.map(t => `<div class="less"><div><strong>${esc(TAGS[t].label)}</strong><span>You passed on ${evidence(t).pass || 'a few'}</span></div>
        <button type="button" class="pill outline small" data-bring="${t}">Bring back</button></div>`).join('')}
    </section>` : ''}

    <section class="card list">
      <label class="setting"><span><strong>Weekly picks</strong><small>Three ideas, at a time you choose</small></span>
        <input type="checkbox" data-set="weekly" ${weekly.enabled ? 'checked' : ''}></label>
      ${weekly.enabled ? `<div class="setting inline">
        <select data-set="weekly-day" aria-label="Day">${DAYS.map(([v, l]) => `<option value="${v}" ${weekly.day === v ? 'selected' : ''}>${l}s</option>`).join('')}</select>
        <input type="time" data-set="weekly-time" value="${esc(weekly.time)}" aria-label="Time"></div>` : ''}
      <label class="setting"><span><strong>“Heading out?” ideas</strong><small>${departureNote(s)}</small></span>
        <input type="checkbox" data-set="departure" ${state.settings.departure ? 'checked' : ''}></label>
      <div class="setting stack"><span><strong>Quiet hours</strong><small>No “heading out” ideas in this window</small></span>
        <span class="times"><input type="time" data-set="quiet-start" value="${esc(state.settings.quiet.start)}" aria-label="Quiet from"><span>to</span><input type="time" data-set="quiet-end" value="${esc(state.settings.quiet.end)}" aria-label="Quiet until"></span></div>
      ${isPhone && !s.notifications ? `<button type="button" class="pill accent wide" data-phone="notifications">${icon('bell', 18)}Allow notifications</button>` : ''}
    </section>

    <section class="card list">
      <div class="setting"><span><strong>Location</strong><small>${esc(state.location ? `${state.location.address || state.location.name || 'Finding your address…'} · ${state.location.mode === 'manual' ? 'chosen place' : 'follows you'}` : 'Not set')}</small></span>
        <a class="pill outline small" href="#/you/location">Change</a></div>
    </section>

    <section class="card" id="keys">
      <h2 class="label">CONNECTIONS</h2>
      <p class="muted">Your own free keys make Roam much better: real photos, opening hours and ratings from Google, and shows near you from Ticketmaster. They stay on this phone.</p>
      <form class="key" data-key="google">
        <label for="k-google"><strong>Google Places</strong> ${keys.google ? `<span class="ok">${icon('check', 14)}Connected ${esc(keys.googleHint || '')}</span>` : ''}</label>
        <div class="key-row"><input id="k-google" name="v" type="password" autocomplete="off" placeholder="${keys.google ? 'Paste a new key to replace' : 'Paste your API key'}"><button class="pill dark small">Save</button></div>
        ${keys.googleProblem ? `<div class="problem"><strong>Google refused this key</strong><p>${esc(friendly(keys.googleProblem))}</p>
          <button type="button" class="text-link" data-url="https://console.cloud.google.com/apis/library/places.googleapis.com">Open “Places API (New)” in Google Cloud ›</button>
          <small>Roam waits 6 hours before trying again, or tries right away when you save a key.</small></div>` : ''}
        ${keys.google ? usage('Place lookups', keys.searchUsed, keys.searchBudget) + usage('Photos', keys.photoUsed, keys.photoBudget)
          + `<p class="fine">Today: ${keys.searchToday || 0} of ${keys.searchPerDay || 40} lookups. Roam reuses results for 24 hours within about 1.5 miles.</p>` : ''}
        <button type="button" class="text-link" data-url="https://console.cloud.google.com/google/maps-apis/credentials">How to get one: Google Cloud console, enable “Places API (New)”, create an API key ›</button>
      </form>
      <form class="key" data-key="ticketmaster">
        <label for="k-tm"><strong>Ticketmaster</strong> ${keys.ticketmaster ? `<span class="ok">${icon('check', 14)}Connected ${esc(keys.ticketmasterHint || '')}</span>` : ''}</label>
        <div class="key-row"><input id="k-tm" name="v" type="password" autocomplete="off" placeholder="${keys.ticketmaster ? 'Paste a new key to replace' : 'Paste your Consumer Key'}"><button class="pill dark small">Save</button></div>
        <button type="button" class="text-link" data-url="https://developer-acct.ticketmaster.com/user/register">Get a free key at developer.ticketmaster.com ›</button>
      </form>
      <p class="fine">Roam stops asking Google once a month's lookups or photos reach the budget above, so you stay inside Google's free monthly allowance. It picks up again on the 1st.</p>
    </section>

    <section class="card list">
      <div class="setting stack"><span><strong>Your data</strong><small>Everything Roam knows lives only on this phone. Save a copy before switching phones.</small></span>
        <span class="btns"><button type="button" class="pill outline small" data-backup>Save a backup</button><button type="button" class="pill outline small" data-restore>Restore</button></span></div>
      <input type="file" id="restore-file" accept="application/json,.json" hidden>
      <div class="setting"><span><strong>Start fresh</strong><small>Forget what Roam learned (keeps Saved)</small></span>
        <button type="button" class="pill outline small" data-reset>Reset tastes</button></div>
    </section>

    <p class="footnote">Roam ${esc(s.version || '')} · Places & photos: Google, or © OpenStreetMap contributors · Events: Ticketmaster · Weather: Open-Meteo</p>`;

  root.onclick = e => {
    const b = e.target.closest('button, a');
    if (!b) return;
    if (b.dataset.bring) { bringBack(b.dataset.bring); save(); app.rerender(); }
    else if (b.dataset.phone) phone.request(b.dataset.phone);
    else if (b.dataset.url) { e.preventDefault(); phone.openURL(b.dataset.url); }
    else if (b.hasAttribute('data-backup')) phone.saveFile(`roam-backup-${new Date().toISOString().slice(0, 10)}.json`, backupText());
    else if (b.hasAttribute('data-restore')) $('#restore-file').click();
    else if (b.hasAttribute('data-reset')) {
      if (b.dataset.confirm) { resetTaste(); toast('Fresh start. Roam will relearn as you go.'); app.rerender(); }
      else { b.dataset.confirm = '1'; b.textContent = 'Tap again to reset'; }
    }
  };
  root.onchange = e => {
    const el = e.target;
    const set = el.dataset.set;
    if (el.id === 'restore-file') return restore(el.files[0], el);
    if (!set) return;
    if (set === 'weekly') state.settings.weekly.enabled = el.checked;
    if (set === 'weekly-day') state.settings.weekly.day = Number(el.value);
    if (set === 'weekly-time' && el.value) state.settings.weekly.time = el.value;
    if (set === 'quiet-start' && el.value) state.settings.quiet.start = el.value;
    if (set === 'quiet-end' && el.value) state.settings.quiet.end = el.value;
    if (set === 'departure') {
      state.settings.departure = el.checked;
      if (el.checked) phone.request('tracking'); else phone.stopTracking();
    }
    if (set === 'weekly' && el.checked && isPhone && !status().notifications) phone.request('notifications');
    save();
    if (set === 'weekly' || set === 'departure') app.rerender();
  };
  root.onsubmit = e => {
    const form = e.target.closest('[data-key]');
    if (!form) return;
    e.preventDefault();
    const value = new FormData(form).get('v').trim();
    if (!/^[A-Za-z0-9_-]{10,200}$/.test(value)) { toast('That doesn’t look like an API key.'); return; }
    phone.setKey(form.dataset.key, value);
    toast('Key saved. Refreshing…');
    app.rerender();
    app.refresh(true);
  };
  if (section === 'keys') setTimeout(() => $('#keys')?.scrollIntoView({ block: 'start' }), 50);
}

/** Google's error text is long and technical; say what to do. */
function friendly(message) {
  if (/has not been used|is disabled|SERVICE_DISABLED/i.test(message)) return 'The “Places API (New)” isn’t turned on in your Google Cloud project yet. Open it below, press Enable, wait a few minutes, then save your key again.';
  if (/billing/i.test(message)) return 'Google needs a billing account on the project before it answers, even within the free allowance.';
  if (/API key not valid|API_KEY_INVALID/i.test(message)) return 'Google says this key isn’t valid. Copy it again from Google Cloud › Credentials.';
  if (/referer|restrict|not authorized/i.test(message)) return 'This key has restrictions that block Roam. In Google Cloud › Credentials, allow the Places API (New) for this key.';
  return message.replace(/^Google:\s*/, '').slice(0, 220);
}

function usage(label, used = 0, budget = 900) {
  const pct = Math.min(100, Math.round(100 * used / budget));
  return `<div class="usage"><span>${label} this month</span><span>${used} of ${budget}</span><div class="bar"><i style="width:${pct}%"></i></div></div>`;
}

function departureNote(s) {
  if (!state.settings.departure) return 'When you leave somewhere, at most once a day';
  if (s.tracking) return 'On · watching for when you head out';
  if (!s.precise) return 'Needs precise location permission';
  return 'Starts when Roam is open';
}

async function restore(file, input) {
  if (!file) return;
  input.value = '';
  try {
    const backup = parseBackup(await file.text());
    if (!confirm(`Restore the backup from ${backup.when ? new Date(backup.when).toLocaleString() : 'this file'}? This replaces what Roam has now.`)) return;
    backup.apply();
    location.reload();
  } catch (e) {
    toast(e.message);
  }
}

// ---- Choosing a location ----------------------------------------------------------------------

function renderLocation(root, app) {
  root.innerHTML = `<header class="sub-head">
      <a class="round" href="#/you" aria-label="Back">${icon('back', 20)}</a>
      <h1>Where to?</h1>
    </header>
    ${state.location ? `<div class="here">${icon('pin', 18)}<span><small>${state.location.mode === 'manual' ? 'Showing ideas around' : 'You’re at'}</small><strong>${esc(state.location.address || state.location.name || 'Finding your address…')}</strong></span></div>` : ''}
    <button type="button" class="pill dark wide" data-here>${icon('locate', 18)}Use where I am</button>
    <form class="search" data-find>
      ${icon('search', 20)}
      <input type="search" name="q" placeholder="A town, neighbourhood or address" aria-label="Search for a place" enterkeyhint="search" autocomplete="off">
    </form>
    <section class="results">${places.map((p, i) => `<button type="button" class="result" data-use="${i}">${icon('pin', 18)}<span>${esc(p.name)}</span></button>`).join('')}</section>
    <p class="muted pad">Planning a trip? Pick where you'll be, and Roam shows what's good there.</p>`;
  root.onclick = e => {
    if (e.target.closest('[data-here]')) {
      state.location = state.location ? { ...state.location, mode: 'live' } : null;
      save();
      app.locate();
      toast('Finding you…');
      app.go('#/today');
      return;
    }
    const use = e.target.closest('[data-use]');
    if (use) {
      const p = places[Number(use.dataset.use)];
      app.setLocation({ lat: p.lat, lng: p.lng, name: p.town || p.name.split(',')[0], address: p.name, mode: 'manual' });
      places = [];
      app.go('#/today');
    }
  };
  root.onsubmit = async e => {
    e.preventDefault();
    const q = new FormData(e.target).get('q').trim();
    if (q.length < 3) return;
    try {
      places = await data.geocode(q);
      if (!places.length) toast('No places found for that.');
    } catch (err) {
      toast(err.message);
    }
    renderLocation(root, app);
  };
}
