// Today: one curated pick (chosen once a day on the phone), two alternatives, and your plans.
// Browsing everything nearby lives in Explore.

import { state, save } from '../store.js';
import { phone, isPhone } from '../native.js';
import { esc, icon, nowLabel, photo, watchImages, toast, miles } from '../util.js';
import * as data from '../data.js';
import { skyIcon } from '../context.js';
import { toggleSave, pass } from '../taste.js';
import { act, plannedRow, link } from './cards.js';
import * as P from '../plans.js';

let pick = null;          // { date, item, words, alternates, ... } or { error }
let loading = false;
let showWhy = false;

async function load(app, another = false, fresh = false) {
  if (loading) return;
  loading = true;
  if (another) app.rerender();
  app.pushSnapshotNow();
  try {
    const r = await fetch('/api/pick' + (another ? '?another=1' : fresh ? '?fresh=1' : ''));
    const next = await r.json();
    if (next.notice) toast(next.notice);
    pick = next;
    for (const x of [next.item, ...(next.alternates || []).map(a => a.item)]) if (x) data.remember(x);
  } catch {
    pick = { error: 'Today’s pick isn’t ready. Check your connection and try again.' };
  } finally {
    loading = false;
    showWhy = false;
    app.rerender();
  }
}

/** Forget the pick on screen (e.g. after a location change) and ask the phone again. */
export function refreshPick(app) { pick = null; load(app); }

export function render(root, _param, app) {
  const origin = app.origin();
  const weather = data.currentWeather();
  if (origin && (!pick || (pick.date && pick.date !== P.today())) && !loading) load(app);
  // Travelled far since the pick was made (say, on a trip): choose again for where you are now.
  else if (origin && pick?.item && !loading && miles(origin, pick.item) > 40) load(app, false, true);

  const header = `<header class="today-head">
    <div>
      <span class="eyebrow">${nowLabel(new Date())}</span>
      <a class="place-name" href="#/you/location">${icon('pin', 16)}${esc(origin?.name || 'Set your location')}</a>
    </div>
    ${weather && !weather.error ? `<span class="weather">${icon(skyIcon(weather), 16)}${weather.tempF}°</span>` : ''}
  </header>`;

  if (!origin) {
    root.innerHTML = `${header}<section class="empty-hero">
      <h1>Where are you?</h1>
      <p>Roam picks one great thing a day near you. It needs a starting point.</p>
      <button type="button" class="pill dark" data-locate>${icon('locate', 18)}Use my location</button>
      <a class="pill outline" href="#/you/location">Choose a place</a>
    </section>`;
    bind(root, app);
    return;
  }

  const todays = P.plannedOn(P.today()).map(data.get).filter(Boolean);
  const nextTrip = P.upcomingTrips().find(t => P.daysBetween(P.today(), t.start) <= 14);

  root.innerHTML = `${header}
    ${pickCard()}
    ${pick?.alternates?.length && !pick.error ? `<section class="alts"><h3>If not that</h3>${pick.alternates.map(alt).join('')}</section>` : ''}
    ${headingOutCard()}
    ${todays.length ? `<section><h3>Today's plans</h3>${todays.map(p => plannedRow(p, origin, P.today())).join('')}</section>` : ''}
    ${nextTrip ? tripBanner(nextTrip) : ''}
    <a class="browse" href="#/explore">Browse everything nearby ${icon('arrow', 18)}</a>`;
  bind(root, app);
  watchImages(root);
}

function pickCard() {
  if (loading && (!pick || pick.error)) {
    return `<article class="daily"><div class="pick-photo skeleton"></div><div class="pick-body"><span class="eyebrow">TODAY'S PICK</span><p class="muted">Choosing today’s pick…</p></div></article>`;
  }
  if (!pick || pick.error) {
    const needsKey = /Google|key/i.test(pick?.error || '');
    return `<article class="daily none"><div class="pick-body">
      <span class="eyebrow">TODAY'S PICK</span>
      <h1 class="pick-headline">${needsKey ? 'Roam needs ratings and reviews to pick well' : 'Nothing worth your time yet'}</h1>
      <p class="muted">${esc(pick?.error || '')}</p>
      ${needsKey ? '<a class="pill dark" href="#/you/keys">Add a Google key</a>' : '<button type="button" class="pill outline" data-retry>Try again</button>'}
    </div></article>`;
  }
  const item = data.get(pick.item.id) || pick.item;
  const w = pick.words || {};
  const saved = !!state.saved[item.id];
  const kind = item.type === 'event' ? (item.genre || 'Event') : item.kind;
  return `<article class="daily${loading ? ' dim' : ''}">
    <a class="pick-photo" href="${link(item)}" aria-label="${esc(item.name)}">${photo(item, 800, 'pick-img')}</a>
    <div class="pick-body">
      <span class="eyebrow accent">TODAY'S PICK${kind ? ' · ' + esc(kind.toUpperCase()) : ''}</span>
      <h1 class="pick-headline">${esc(w.headline || item.name)}</h1>
      <a class="pick-name" href="${link(item)}">${esc(item.name)} ${icon('arrow', 16)}</a>
      ${w.practical ? `<p class="pick-practical">${esc(w.practical)}</p>` : ''}
      ${w.about ? `<p class="pick-about">${esc(w.about)}</p>` : ''}
      ${w.quote ? `<blockquote class="pick-quote">“${esc(w.quote.text)}”${w.quote.by ? `<cite>${esc(w.quote.by)}, Google review</cite>` : ''}</blockquote>` : ''}
      ${w.highlights?.length ? `<p class="pick-mentions"><span>People mention</span>${w.highlights.map(h => `<b>${esc(h)}</b>`).join('')}</p>` : ''}
      ${w.why?.length ? `<ul class="pick-why">${w.why.map(x => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
      <div class="pick-actions">
        <button type="button" class="pill accent grow" data-act="go" data-id="${esc(item.id)}">${icon('go', 18)}Let's go</button>
        <button type="button" class="pill outline ${saved ? 'on' : ''}" data-into>${saved ? `${icon('check', 18)}Saved` : 'Into it'}</button>
        <a class="round bordered" href="${link(item)}" aria-label="Plan it or see more">${icon('calendar', 20)}</a>
      </div>
      ${showWhy ? `<div class="pass-reasons"><span>What’s off about it?</span><div class="chips">
        <button type="button" class="chip-btn" data-pass="far">Too far</button>
        <button type="button" class="chip-btn" data-pass="vibe">Not my vibe</button>
        ${item.type === 'event' ? '' : '<button type="button" class="chip-btn" data-pass="been">Been there</button>'}
        <button type="button" class="chip-btn" data-pass="later">Not today</button></div></div>`
        : `<button type="button" class="text-link quiet" data-notforme>Not for me? Show me another</button>`}
    </div>
  </article>`;
}

function alt(a) {
  const item = data.get(a.item.id) || a.item;
  return `<a class="alt" href="${link(item)}">
    ${photo(item, 200, 'thumb')}
    <span class="row-text"><strong>${esc(item.name)}</strong><span class="why">${esc(a.words?.headline || '')}</span>
    <span class="meta">${esc(a.words?.practical || '')}</span></span>
  </a>`;
}

/** One-time offer to turn on "Heading out?" ideas; it needs location while Roam is closed. */
function headingOutCard() {
  if (state.settings.departure || state.settings.headingOutAsked || !pick?.item) return '';
  return `<section class="offer">
    <span class="offer-icon">${icon('go', 20)}</span>
    <div><strong>Want a nudge when you head out?</strong>
    <p>When you leave somewhere you've spent a while, Roam checks what's close by and only speaks up if something clears the same bar as this pick. Twice a day at most, never when you leave home.</p>
    <div class="offer-actions"><button type="button" class="pill dark" data-headout="on">Turn it on</button><button type="button" class="text-link quiet" data-headout="no">No thanks</button></div></div>
  </section>`;
}

function tripBanner(t) {
  const until = P.daysBetween(P.today(), t.start);
  const n = P.range(t.start, t.end).reduce((sum, d) => sum + P.plannedOn(d).length, 0);
  const when = until <= 0 ? 'You’re on it' : until === 1 ? 'Tomorrow' : `In ${until} days`;
  return `<a class="hint trip-banner" href="#/plans/trip/${encodeURIComponent(t.id)}"><p>${icon('calendar', 18)}<span><strong>${esc(t.name)}</strong> · ${when}. ${n ? `${n} planned.` : 'See ideas and events for your dates.'}</span></p><span class="go">${icon('arrow', 18)}</span></a>`;
}

function bind(root, app) {
  root.onclick = e => {
    if (act(e, app)) return;
    if (e.target.closest('[data-locate]')) { app.locate(); return; }
    if (e.target.closest('[data-retry]')) { refreshPick(app); return; }
    const ho = e.target.closest('[data-headout]');
    if (ho) {
      state.settings.headingOutAsked = true;
      if (ho.dataset.headout === 'on') {
        state.settings.departure = true;
        if (isPhone) phone.request('tracking');
        toast('On. Roam will only speak up when it’s worth it.');
      }
      save();
      app.rerender();
      return;
    }
    const item = pick?.item && (data.get(pick.item.id) || pick.item);
    if (!item) return;
    if (e.target.closest('[data-into]')) {
      if (!state.saved[item.id]) { toggleSave(item); save(); toast('Saved. Roam will look for more like this.'); }
      app.rerender();
      return;
    }
    if (e.target.closest('[data-notforme]')) { showWhy = true; app.rerender(); return; }
    const r = e.target.closest('[data-pass]');
    if (r) {
      pass(item, r.dataset.pass);
      save();
      toast({ far: 'Noted. Keeping picks closer.', vibe: 'Got it. Less like this.', been: 'Marked as been there.', later: 'Okay, here’s another.' }[r.dataset.pass]);
      // The phone needs the updated "not this" list before choosing again.
      app.pushSnapshotNow();
      load(app, true);
    }
  };
}
