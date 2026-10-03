// The first minute: tap what sounds good, then go.

import { state } from '../store.js';
import { esc, icon } from '../util.js';
import { seed } from '../taste.js';

const TILES = [
  ['coffee', 'Slow coffee', ['coffee']],
  ['views', 'Big views', ['views', 'trails']],
  ['music', 'Live music', ['music']],
  ['quiet', 'Tea & quiet', ['quiet', 'coffee']],
  ['gardens', 'Gardens', ['gardens', 'outdoors']],
  ['trails', 'Easy trails', ['trails', 'outdoors']],
  ['food', 'Good food', ['food']],
  ['books', 'Bookshops', ['books']],
  ['drinks', 'Patio drinks', ['drinks']],
];
const picked = new Set();

export function render(root, _param, app) {
  root.innerHTML = `<div class="welcome">
    <span class="brand">roam<b>.</b></span>
    <h1>What sounds good lately?</h1>
    <p class="lede">Tap a few. Roam learns the rest from what you save and skip.</p>
    <div class="tile-grid">
      ${TILES.map(([k, label]) => `<button type="button" class="pick ${picked.has(k) ? 'on' : ''}" data-pick="${k}" aria-pressed="${picked.has(k)}">
        <img src="img/w-${k}.jpg" alt="">
        ${picked.has(k) ? `<span class="tick">${icon('check', 14)}</span>` : ''}
        <span class="label">${esc(label)}</span>
      </button>`).join('')}
    </div>
    <div class="welcome-actions">
      <button type="button" class="pill dark wide" data-start>${picked.size ? "Show me what's good" : 'Pick a few, or just start'}${icon('arrow', 18)}</button>
      <p class="muted center">Your location and tastes stay on this phone.</p>
    </div>
  </div>`;
  root.onclick = e => {
    const p = e.target.closest('[data-pick]');
    if (p) {
      const k = p.dataset.pick;
      picked.has(k) ? picked.delete(k) : picked.add(k);
      app.rerender();
      return;
    }
    if (e.target.closest('[data-start]')) {
      const tags = [...new Set(TILES.filter(([k]) => picked.has(k)).flatMap(t => t[2]))];
      seed(tags);
      state.onboarded = true;
      app.go('#/today');
      if (state.location?.mode !== 'manual') app.locate();
      if (state.location) app.refresh();
    }
  };
}
