// One place or event: photos, why it's for you, the practical bits, and quick feedback.

import { state } from '../store.js';
import { esc, icon, photoSrc, miles, distanceLabel, travelLabel, eventWhen, watchImages } from '../util.js';
import * as data from '../data.js';
import { openState, reasonLong, opened, score, available, compact } from '../taste.js';
import { saveButton, act, link } from './cards.js';

export function render(root, id, app) {
  const item = data.get(id);
  if (!item) {
    root.innerHTML = `<section class="empty-hero"><h1>That one's gone</h1><p>Roam doesn't have this place loaded any more.</p><a class="pill dark" href="#/today">Back to Today</a></section>`;
    return;
  }
  opened(item);
  const origin = app.origin();
  const d = origin ? miles(origin, item) : NaN;
  const isEvent = item.type === 'event';
  const photos = isEvent ? (item.image ? [item.image] : []) : (item.photos || []).map((_, i) => photoSrc(item, 800, i));
  const why = reasonLong(item);
  const open = openState(item);
  const chips = [
    open.known ? `<span class="chip ${open.open ? 'good' : ''}">${open.open ? '<i class="dot"></i>' : ''}${esc(open.label)}</span>` : '',
    Number.isFinite(d) ? `<span class="chip">${distanceLabel(d)} · ${travelLabel(d)}</span>` : '',
    item.rating ? `<span class="chip">${icon('star', 14)}${item.rating.toFixed(1)} · ${compact(item.ratings || 0)}</span>` : '',
    item.price ? `<span class="chip">${'$'.repeat(item.price)}</span>` : '',
  ].join('');

  root.innerHTML = `<div class="detail">
    <div class="gallery">
      ${photos.length ? `<div class="slides">${photos.map((src, i) => `<img src="${esc(src)}" alt="${i ? '' : esc(item.name)}" loading="${i ? 'lazy' : 'eager'}" data-fade>`).join('')}</div>`
        : `<div class="slides none"><span>${esc(item.name.slice(0, 1))}</span></div>`}
      <div class="gallery-top">
        <button type="button" class="round" data-back aria-label="Back">${icon('back', 20)}</button>
        ${saveButton(item)}
      </div>
      ${photos.length > 1 ? `<span class="count">${photos.length} photos</span>` : ''}
    </div>
    <div class="sheet">
      <span class="eyebrow">${esc(isEvent ? eventWhen(item).toUpperCase() : (item.kind || '').toUpperCase())}</span>
      <h1>${esc(item.name)}</h1>
      ${isEvent ? `<p class="sub">${esc([item.venue, item.city].filter(Boolean).join(', '))}${item.genre ? ` · ${esc(item.genre)}` : ''}</p>` : ''}
      <div class="chips">${chips}</div>

      <section class="why-card">
        <span class="why-title">${icon('spark', 18)}WHY IT'S FOR YOU</span>
        <p>${esc(why.text)}</p>
        ${why.from.length ? `<span class="from">Learned from: ${why.from.map(esc).join(', ')}</span>` : ''}
      </section>

      <section class="facts">
        <h2>Good to know</h2>
        ${isEvent ? eventFacts(item) : placeFacts(item, open)}
      </section>

      ${isEvent ? '' : pairing(item, origin, app)}

      <section class="feedback">
        <h2>Not for you? Tell Roam why</h2>
        <div class="chips">
          <button type="button" class="chip-btn" data-act="pass" data-reason="far" data-id="${esc(item.id)}" data-stay="1">Too far</button>
          <button type="button" class="chip-btn" data-act="pass" data-reason="vibe" data-id="${esc(item.id)}" data-stay="1">Not my vibe</button>
          ${isEvent ? '' : `<button type="button" class="chip-btn" data-act="pass" data-reason="been" data-id="${esc(item.id)}" data-stay="1">Been there</button>`}
          <button type="button" class="chip-btn" data-act="pass" data-reason="later" data-id="${esc(item.id)}" data-stay="1">Wrong time</button>
        </div>
      </section>
      ${photoCredit(item)}
    </div>
    <div class="action-bar">
      ${isEvent && item.url
        ? `<button type="button" class="pill accent grow" data-act="url" data-url="${esc(item.url)}" data-id="${esc(item.id)}">${icon('ticket', 18)}Tickets</button>
           <button type="button" class="pill outline" data-act="go" data-id="${esc(item.id)}">${icon('go', 18)}Go</button>`
        : `<button type="button" class="pill accent grow" data-act="go" data-id="${esc(item.id)}">${icon('go', 18)}Directions</button>
           <button type="button" class="pill outline ${state.went[item.id] ? 'on' : ''}" data-act="went" data-id="${esc(item.id)}">${state.went[item.id] ? `${icon('check', 18)}Went` : 'I went'}</button>`}
      <button type="button" class="round" data-act="share" data-id="${esc(item.id)}" aria-label="Share">${icon('share', 20)}</button>
    </div>
  </div>`;

  root.onclick = e => {
    if (e.target.closest('[data-back]')) { history.length > 1 ? history.back() : app.go('#/today'); return; }
    const pass = e.target.closest('[data-act="pass"]');
    if (act(e, app) && pass) { history.length > 1 ? history.back() : app.go('#/today'); }
  };
  watchImages(root);
}

function placeFacts(item, open) {
  const todayText = todayHours(item);
  const rows = [];
  if (todayText || open.known) {
    rows.push(`<details class="fact"><summary>${icon('clock', 20)}<span>${esc(todayText || open.label)}</span></summary>
      ${item.hours?.text?.length > 1 ? `<ul>${item.hours.text.map(t => `<li>${esc(t)}</li>`).join('')}</ul>` : ''}</details>`);
  }
  if (item.address) rows.push(`<div class="fact">${icon('pin', 20)}<span>${esc(item.address)}</span></div>`);
  if (item.website) rows.push(`<button type="button" class="fact link" data-act="url" data-url="${esc(item.website)}" data-id="${esc(item.id)}">${icon('globe', 20)}<span>${esc(host(item.website))}</span></button>`);
  if (item.mapsUrl) rows.push(`<button type="button" class="fact link" data-act="url" data-url="${esc(item.mapsUrl)}" data-id="${esc(item.id)}">${icon('star', 20)}<span>Reviews and more on Google Maps</span></button>`);
  return rows.join('') || '<p class="muted">Roam doesn’t have hours or contact details for this one.</p>';
}

function eventFacts(e) {
  const rows = [`<div class="fact">${icon('calendar', 20)}<span>${esc(new Date(e.start).toLocaleString([], { weekday: 'long', month: 'long', day: 'numeric', ...(e.localTime ? { hour: 'numeric', minute: '2-digit' } : {}) }))}</span></div>`];
  if (e.moreDates) rows.push(`<div class="fact">${icon('spark', 20)}<span>${e.moreDates} more date${e.moreDates > 1 ? 's' : ''} on Ticketmaster</span></div>`);
  if (e.priceFrom) rows.push(`<div class="fact">${icon('ticket', 20)}<span>From ${e.currency === 'USD' ? '$' : ''}${Math.round(e.priceFrom)}${e.currency && e.currency !== 'USD' ? ' ' + esc(e.currency) : ''}</span></div>`);
  return rows.join('');
}

function todayHours(item) {
  const text = item.hours?.text;
  if (!text?.length) return '';
  if (text.length === 1) return text[0];
  // Google lists Monday first.
  const idx = (new Date().getDay() + 6) % 7;
  return (text[idx] || '').replace(/^[A-Za-z]+:\s*/, 'Today: ');
}

function pairing(item, origin, app) {
  const ctx = app.ctx();
  const near = data.places().filter(p => p.id !== item.id && available(p) && miles(item, p) < 1.5
    && !p.tags.some(t => t === item.tags[0]) && p.photos?.length)
    .map(p => ({ p, s: score(p, ctx, item) })).sort((a, b) => b.s - a.s)[0]?.p;
  if (!near) return '';
  const d = miles(item, near);
  return `<section class="pair"><h2>Pair it with</h2>
    <a class="row-link" href="${link(near)}">
      <span class="ph thumb"><img src="${esc(photoSrc(near, 200))}" alt="" loading="lazy" data-fade></span>
      <span class="row-text"><strong>${esc(near.name)}</strong><span class="meta">${esc(near.kind || '')} · ${travelLabel(d)} from here</span></span>
    </a></section>`;
}

function photoCredit(item) {
  const by = item.photos?.map(p => p.by).filter(Boolean);
  if (item.type === 'event') return '<p class="credit">Event details and image from Ticketmaster</p>';
  if (!by?.length) return '';
  return `<p class="credit">Photos: ${[...new Set(by)].slice(0, 3).map(esc).join(', ')}${item.source === 'google' ? ' · via Google' : ''}</p>`;
}

function host(url) {
  try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return url; }
}
