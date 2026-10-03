// Saved: places and events you want to go to, and the ones you've been to.

import { state } from '../store.js';
import { icon, watchImages } from '../util.js';
import * as data from '../data.js';
import { row, act } from './cards.js';

let tab = 'want';

export function render(root, _param, app) {
  const origin = app.origin();
  const all = Object.values(state.saved).map(s => ({ ...s, ...(data.get(s.id) || {}) }))
    .filter(s => s.type !== 'event' || s.start > Date.now() - 86400000);
  const want = all.filter(s => !state.went[s.id]).sort((a, b) => (b.savedAt || 0) - (a.savedAt || 0));
  const been = Object.keys(state.went).map(id => data.get(id)).filter(Boolean)
    .sort((a, b) => state.went[b.id] - state.went[a.id]);
  const list = tab === 'want' ? want : been;

  root.innerHTML = `<h1 class="page-title">Saved</h1>
    <div class="segment" role="tablist">
      <button type="button" role="tab" class="${tab === 'want' ? 'on' : ''}" aria-selected="${tab === 'want'}" data-tab="want">Want to go · ${want.length}</button>
      <button type="button" role="tab" class="${tab === 'been' ? 'on' : ''}" aria-selected="${tab === 'been'}" data-tab="been">Been · ${been.length}</button>
    </div>
    <section>${list.length ? list.map(p => row(p, origin, null, tab === 'been' ? `Went ${ago(state.went[p.id])}` : p.savedAt ? `Saved ${ago(p.savedAt)}` : undefined)).join('')
      : `<div class="empty">${icon('bookmark', 28)}<p>${tab === 'want' ? 'Tap the bookmark on anything that catches your eye. Roam learns from every save.' : 'Tap “I went” on a place and it shows up here. It’s the strongest signal you can give Roam.'}</p></div>`}</section>`;
  root.onclick = e => {
    if (act(e, app)) return;
    const t = e.target.closest('[data-tab]');
    if (t) { tab = t.dataset.tab; app.rerender(); }
  };
  watchImages(root);
}

function ago(t) {
  const days = Math.floor((Date.now() - t) / 86400000);
  return days < 1 ? 'today' : days === 1 ? 'yesterday' : days < 14 ? `${days} days ago` : `${Math.round(days / 7)} weeks ago`;
}
