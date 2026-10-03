// Today: one great idea for right now, then what's coming up, then a few more worth a detour.

import { state } from '../store.js';
import { $, esc, icon, nowLabel, miles, watchImages } from '../util.js';
import { status } from '../native.js';
import * as data from '../data.js';
import { skyIcon } from '../context.js';
import { score, available, diversify, shown, openState, tasteOf } from '../taste.js';
import { hero, row, eventCard, skeleton, act, plannedRow } from './cards.js';
import * as P from '../plans.js';

const FILTERS = [
  ['now', 'Right now', null],
  ['outside', 'Outside', ['outdoors', 'trails', 'views', 'gardens']],
  ['coffee', 'Coffee & tea', ['coffee', 'sweet', 'quiet']],
  ['food', 'Food', ['food']],
  ['drinks', 'Drinks', ['drinks', 'nightlife']],
  ['culture', 'Culture', ['culture', 'books', 'sights', 'music']],
  ['new', 'Somewhere new', 'new'],
];
let filter = 'now';
let shownCount = 6;

export function render(root, _param, app) {
  const origin = app.origin();
  const ctx = app.ctx();
  const weather = data.currentWeather();
  const loading = app.isLoading();

  const header = `<header class="today-head">
    <div>
      <span class="eyebrow">${nowLabel(ctx.date)}</span>
      <a class="place-name" href="#/you/location">${icon('pin', 16)}${esc(origin?.name || 'Set your location')}</a>
    </div>
    <div class="head-actions">
      ${weather && !weather.error ? `<span class="weather">${icon(skyIcon(weather), 16)}${weather.tempF}°</span>` : ''}
      <button type="button" class="round small" data-refresh aria-label="Refresh">${icon('refresh', 18)}</button>
    </div>
  </header>`;

  if (!origin) {
    root.innerHTML = `${header}<section class="empty-hero">
      <h1>Where are you?</h1>
      <p>Roam needs a starting point to find good things nearby.</p>
      <button type="button" class="pill dark" data-locate>${icon('locate', 18)}Use my location</button>
      <a class="pill outline" href="#/you/location">Choose a place</a>
    </section>`;
    bind(root, app);
    return;
  }

  const [, , tags] = FILTERS.find(f => f[0] === filter);
  const nearby = data.places().filter(p => available(p) && miles(origin, p) < 30);
  let pool = nearby;
  if (Array.isArray(tags)) pool = nearby.filter(p => p.tags.some(t => tags.includes(t)));
  if (tags === 'new') pool = nearby.filter(p => Math.abs(tasteOf(p.tags)) < 0.5 && !state.went[p.id] && !state.saved[p.id]);
  const scoreOf = p => score(p, ctx, origin);

  const ranked = diversify(pool, scoreOf, shownCount + 1);
  const heroPick = ranked.find(p => p.photos?.length && openState(p, ctx.date).open !== false) || ranked[0];
  const rest = ranked.filter(p => p !== heroPick).slice(0, shownCount);
  if (heroPick) shown(heroPick);

  const upcoming = data.events().filter(e => available(e) && e.start < Date.now() + 14 * 86400000)
    .map(e => ({ e, s: score(e, ctx, origin) })).sort((a, b) => b.s - a.s).slice(0, 8).map(x => x.e)
    .sort((a, b) => a.start - b.start);

  const weekly = status().weeklyLast;
  const picks = weekly?.at && Date.now() - weekly.at < 3 * 86400000 ? (weekly.ids || []).map(data.get).filter(Boolean) : [];
  const info = data.placesInfo();
  const evInfo = data.eventsInfo();
  const todays = P.plannedOn(P.today()).map(data.get).filter(Boolean);
  const nextTrip = P.upcomingTrips().find(t => P.daysBetween(P.today(), t.start) <= 14);

  root.innerHTML = `${header}
    <h1 class="headline">${esc(ctx.headline)}</h1>
    ${heroPick ? hero(heroPick, origin, ctx) : loading ? skeleton('hero') : emptyPool(nearby.length)}
    <div class="filters" role="tablist" aria-label="What are you in the mood for?">
      ${FILTERS.map(([k, label]) => `<button type="button" role="tab" class="filter ${k === filter ? 'on' : ''}" aria-selected="${k === filter}" data-filter="${k}">${label}</button>`).join('')}
    </div>
    ${todays.length ? `<section><h3>Today's plans</h3>${todays.map(p => plannedRow(p, origin, P.today())).join('')}</section>` : ''}
    ${nextTrip ? tripBanner(nextTrip) : ''}
    ${picks.length ? `<section id="picks"><h3>This week's picks</h3>${picks.map(p => row(p, origin, ctx)).join('')}</section>` : ''}
    ${upcoming.length ? `<section><div class="section-head"><h3>Coming up near you</h3></div><div class="rail">${upcoming.map(e => eventCard(e, origin)).join('')}</div></section>`
      : evInfo.needsKey ? `<section class="hint"><p>${icon('ticket', 18)} See concerts, games and shows near you by adding a free Ticketmaster key.</p><a href="#/you/keys">Add key</a></section>` : ''}
    <section>
      <h3>Worth a detour</h3>
      ${rest.length ? rest.map(p => row(p, origin, ctx)).join('') : loading ? skeleton('rows', 4) : ''}
      ${ranked.length > shownCount ? '<button type="button" class="pill outline wide" data-more>Show me more</button>' : ''}
    </section>
    ${info.source === 'osm' ? `<section class="hint"><p>${icon('spark', 18)} You're seeing free map data. Add a Google Places key for photos, hours and ratings.</p><a href="#/you/keys">Add key</a></section>` : ''}
    <p class="footnote">${loading ? 'Looking around…' : info.at ? `Checked ${new Date(info.at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })} · ${info.source === 'google' ? 'Places & photos from Google' : 'Map data © OpenStreetMap contributors'}` : ''}</p>`;
  bind(root, app);
  watchImages(root);
}

function tripBanner(t) {
  const until = P.daysBetween(P.today(), t.start);
  const n = P.range(t.start, t.end).reduce((sum, d) => sum + P.plannedOn(d).length, 0);
  const when = until <= 0 ? 'You’re on it' : until === 1 ? 'Tomorrow' : `In ${until} days`;
  return `<a class="hint trip-banner" href="#/plans/trip/${encodeURIComponent(t.id)}"><p>${icon('calendar', 18)}<span><strong>${esc(t.name)}</strong> · ${when}. ${n ? `${n} planned.` : 'See ideas and events for your dates.'}</span></p><span class="go">${icon('arrow', 18)}</span></a>`;
}

function emptyPool(total) {
  if (!total) return `<section class="empty-hero"><h1>Nothing loaded yet</h1><p>Check your connection, then refresh.</p><button type="button" class="pill dark" data-refresh>Try again</button></section>`;
  return `<section class="empty-hero small"><p>Nothing matches that right now. Try another mood.</p></section>`;
}

function bind(root, app) {
  root.onclick = e => {
    if (act(e, app)) return;
    const f = e.target.closest('[data-filter]');
    if (f) { filter = f.dataset.filter; shownCount = 6; app.rerender(); return; }
    if (e.target.closest('[data-more]')) { shownCount += 6; app.rerender(); return; }
    // Refresh re-reads what the phone has; it only goes back to Google when results are old.
    if (e.target.closest('[data-refresh]')) { app.refresh(true); return; }
    if (e.target.closest('[data-locate]')) { app.locate(); }
  };
}
