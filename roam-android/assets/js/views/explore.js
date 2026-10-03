// Explore: search for anything, or browse by kind, best-for-you first.

import { esc, icon, miles, watchImages, toast } from '../util.js';
import * as data from '../data.js';
import { TAGS, score, available, openState } from '../taste.js';
import { row, act } from './cards.js';

const BROWSE = ['coffee', 'food', 'drinks', 'outdoors', 'trails', 'views', 'culture', 'books', 'sweet', 'fun', 'gardens', 'nightlife', 'wellness', 'music'];
let query = '';
let results = null;
let sort = 'best';
let busy = false;

export function render(root, tag, app) {
  const origin = app.origin();
  const ctx = app.ctx();
  if (!origin) { root.innerHTML = '<section class="empty-hero"><h1>Explore</h1><p>Set your location first.</p><a class="pill dark" href="#/you/location">Choose a place</a></section>'; return; }

  let list = null, title = '';
  if (tag && TAGS[tag]) {
    title = TAGS[tag].label;
    list = tag === 'music'
      ? data.events().filter(e => available(e) && e.tags.includes('music'))
      : data.places().filter(p => available(p) && p.tags.includes(tag) && miles(origin, p) < 30);
  } else if (tag === 'search' && results) {
    title = `“${query}”`;
    list = results;
  } else if (!tag) {
    results = null;
  }

  if (list) {
    const sorted = sortList(list, origin, ctx);
    root.innerHTML = `<header class="sub-head">
        <a class="round" href="#/explore" aria-label="Back to Explore">${icon('back', 20)}</a>
        <h1>${esc(title)}</h1>
      </header>
      <div class="filters">
        ${[['best', 'Best for me'], ['near', 'Nearest'], ['open', 'Open now']].map(([k, l]) => `<button type="button" class="filter ${sort === k ? 'on' : ''}" data-sort="${k}">${l}</button>`).join('')}
      </div>
      <section>${sorted.length ? sorted.slice(0, 40).map(p => row(p, origin, ctx)).join('') : '<p class="muted pad">Nothing here yet. Try a search, or another area.</p>'}</section>`;
  } else {
    root.innerHTML = `<h1 class="page-title">Explore</h1>
      <form class="search" role="search" data-search>
        ${icon('search', 20)}
        <input type="search" name="q" placeholder="Tacos, climbing gym, rooftop…" value="${esc(query)}" aria-label="Search places" enterkeyhint="search" autocomplete="off">
        ${busy ? '<span class="spinner" aria-label="Searching"></span>' : ''}
      </form>
      <div class="tiles">
        ${BROWSE.map(t => `<a class="tile" href="#/explore/${t}"><strong>${esc(TAGS[t].label)}</strong><span>${count(t, origin)}</span></a>`).join('')}
      </div>
      <p class="footnote">Exploring around ${esc(origin.name || 'you')} · <a href="#/you/location">change</a></p>`;
  }

  root.onclick = e => {
    if (act(e, app)) return;
    const s = e.target.closest('[data-sort]');
    if (s) { sort = s.dataset.sort; app.rerender(); }
  };
  root.onsubmit = e => {
    if (!e.target.matches('[data-search]')) return;
    e.preventDefault();
    const text = new FormData(e.target).get('q').trim();
    if (text) searchFor(text, app);
  };
  watchImages(root);
}

function count(tag, origin) {
  if (tag === 'music') {
    const n = data.events().filter(e => e.tags.includes('music')).length;
    return n ? `${n} shows` : 'Shows';
  }
  const n = data.places().filter(p => p.tags.includes(tag) && miles(origin, p) < 30).length;
  return n ? `${n} nearby` : 'Search';
}

function sortList(list, origin, ctx) {
  if (sort === 'near') return [...list].sort((a, b) => miles(origin, a) - miles(origin, b));
  if (sort === 'open') return list.filter(p => openState(p, ctx.date).open).sort((a, b) => score(b, ctx, origin) - score(a, ctx, origin));
  return [...list].sort((a, b) => score(b, ctx, origin) - score(a, ctx, origin));
}

export async function searchFor(text, app) {
  query = text.slice(0, 120);
  busy = true;
  app.rerender();
  try {
    results = await data.search(query, app.origin());
    if (!results.length) toast('Nothing found nearby for that.');
  } catch (e) {
    toast(e.message);
    results = null;
  } finally {
    busy = false;
    if (results) { sort = 'best'; app.go('#/explore/search'); } else app.rerender();
  }
}
