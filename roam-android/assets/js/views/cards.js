// Building blocks shared by the screens, and the actions they all understand.

import { state, save } from '../store.js';
import { phone } from '../native.js';
import { esc, icon, photo, photoSrc, miles, distanceLabel, travelLabel, eventWhen, toast } from '../util.js';
import { openState, reason, toggleSave, pass, went, compact } from '../taste.js';
import * as data from '../data.js';
import { plan, unplan, shortDay } from '../plans.js';

export const link = item => `#/place/${encodeURIComponent(item.id)}`;

export function saveButton(item, cls = 'round') {
  const on = !!state.saved[item.id];
  return `<button type="button" class="${cls} save ${on ? 'on' : ''}" data-act="save" data-id="${esc(item.id)}" aria-pressed="${on}" aria-label="${on ? 'Saved' : 'Save'} ${esc(item.name)}">${icon('bookmark', 20)}</button>`;
}

export function metaLine(item, origin) {
  const parts = [];
  if (item.type === 'event') {
    parts.push(eventWhen(item));
    if (item.venue) parts.push(esc(item.venue));
  } else {
    if (item.kind) parts.push(esc(item.kind));
    if (origin) parts.push(distanceLabel(miles(origin, item)));
    if (item.rating) parts.push(`★ ${item.rating.toFixed(1)}`);
    const open = openState(item);
    if (open.known) parts.push(`<span class="${open.open ? 'open' : 'closed'}">${esc(open.label)}</span>`);
  }
  return parts.join(' · ');
}

export function hero(item, origin, ctx) {
  const d = origin ? miles(origin, item) : NaN;
  const open = openState(item);
  const plain = !photoSrc(item);
  return `<article class="hero${plain ? ' plain' : ''}">
    ${plain ? '' : `<a class="hero-link" href="${link(item)}" aria-label="${esc(item.name)}">${photo(item, 800, 'hero-photo', '')}</a>`}
    <div class="hero-top"><span class="badge">PICKED FOR RIGHT NOW</span>${saveButton(item)}</div>
    <div class="hero-body">
      <div class="chips">${open.known ? `<span class="chip glass">${esc(open.label)}</span>` : ''}${Number.isFinite(d) ? `<span class="chip glass">${distanceLabel(d)} · ${travelLabel(d)}</span>` : ''}</div>
      <h2><a href="${link(item)}">${esc(item.name)}</a></h2>
      <p>${esc(reason(item, origin, ctx) || (item.rating ? `Rated ${item.rating.toFixed(1)} by ${compact(item.ratings || 0)} people` : item.kind || ''))}</p>
      <div class="hero-actions">
        <button type="button" class="pill light" data-act="go" data-id="${esc(item.id)}">${icon('go', 18)}Let's go</button>
        <button type="button" class="pill ghost" data-act="pass" data-reason="later" data-id="${esc(item.id)}">Not now</button>
      </div>
    </div>
  </article>`;
}

export function row(item, origin, ctx, note) {
  return `<div class="row">
    <a class="row-link" href="${link(item)}">
      ${photo(item, 200, 'thumb')}
      <span class="row-text">
        <strong>${esc(item.name)}</strong>
        <span class="meta">${metaLine(item, origin)}</span>
        ${(note ?? reason(item, origin, ctx)) ? `<span class="why">${esc(note ?? reason(item, origin, ctx))}</span>` : ''}
      </span>
    </a>
    ${saveButton(item)}
  </div>`;
}

/** A row whose button puts the item on a day ("+ Sat"), or shows it's already there. */
export function planRow(item, origin, date, label) {
  const on = (state.plans[date] || []).includes(item.id);
  return `<div class="row">
    <a class="row-link" href="${link(item)}">
      ${photo(item, 200, 'thumb')}
      <span class="row-text"><strong>${esc(item.name)}</strong><span class="meta">${metaLine(item, origin)}</span></span>
    </a>
    <button type="button" class="pill small ${on ? 'outline on' : 'dark'}" data-act="${on ? 'unplan' : 'plan'}" data-id="${esc(item.id)}" data-date="${date}">${on ? `${icon('check', 16)}Planned` : `+ ${esc(label)}`}</button>
  </div>`;
}

/** Something already planned for a day, with a remove button. */
export function plannedRow(item, origin, date) {
  return `<div class="row">
    <a class="row-link" href="${link(item)}">
      ${photo(item, 200, 'thumb')}
      <span class="row-text"><strong>${esc(item.name)}</strong><span class="meta">${metaLine(item, origin)}</span></span>
    </a>
    <button type="button" class="round small plain" data-act="unplan" data-id="${esc(item.id)}" data-date="${date}" aria-label="Remove ${esc(item.name)} from this day">${icon('x', 18)}</button>
  </div>`;
}

export function eventCard(item, origin) {
  const d = origin ? miles(origin, item) : NaN;
  return `<article class="event">
    <a href="${link(item)}">
      ${photo(item, 600, 'event-photo')}
      <span class="when">${esc(eventWhen(item).toUpperCase())}</span>
      <strong>${esc(item.name)}</strong>
      <span class="meta">${esc(item.venue || '')}${Number.isFinite(d) ? ` · ${travelLabel(d)}` : ''}</span>
    </a>
  </article>`;
}

export function skeleton(kind = 'rows', n = 3) {
  if (kind === 'hero') return '<div class="hero skeleton"></div>';
  return Array.from({ length: n }, () => '<div class="row skeleton-row"><span class="thumb skeleton"></span><span class="lines"><span class="skeleton"></span><span class="skeleton short"></span></span></div>').join('');
}

/** Clicks on [data-act] anywhere in a screen. Returns true when handled. */
export function act(e, app) {
  const b = e.target.closest('[data-act]');
  if (!b) return false;
  e.preventDefault();
  const item = data.get(b.dataset.id);
  const what = b.dataset.act;
  if (!item && what !== 'reason') return true;
  if (what === 'save') {
    const on = toggleSave(item);
    save();
    toast(on ? `Saved ${item.name}` : 'Removed from Saved');
    for (const btn of document.querySelectorAll(`[data-act="save"][data-id="${CSS.escape(item.id)}"]`)) {
      btn.classList.toggle('on', on);
      btn.setAttribute('aria-pressed', on);
    }
  } else if (what === 'pass') {
    const reasonKey = b.dataset.reason;
    pass(item, reasonKey);
    save();
    toast({ later: 'Okay, not today.', vibe: 'Got it. Less like this.', far: 'Noted. Keeping things closer.', been: 'Marked as been there.' }[reasonKey] || 'Noted.');
    if (b.dataset.stay !== '1') app.rerender();
  } else if (what === 'went') {
    const on = went(item);
    save();
    toast(on ? 'Nice. Roam will find more like it.' : 'Okay, unmarked.');
    app.rerender();
  } else if (what === 'plan') {
    plan(b.dataset.date, item);
    toast(`Planned for ${shortDay(b.dataset.date)}`);
    app.rerender();
  } else if (what === 'unplan') {
    unplan(b.dataset.date, item.id);
    toast('Removed from that day');
    app.rerender();
  } else if (what === 'go') {
    phone.directions(item.lat, item.lng);
  } else if (what === 'share') {
    phone.share(`${item.name}${item.mapsUrl ? '\n' + item.mapsUrl : item.url ? '\n' + item.url : ''}`);
  } else if (what === 'url') {
    const url = b.dataset.url;
    if (url?.startsWith('https://') || url?.startsWith('http://')) phone.openURL(url.replace(/^http:/, 'https:'));
  }
  return true;
}
