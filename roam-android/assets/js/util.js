// Small shared helpers: escaping, icons, distances, time labels, toasts.

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

export function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

const ICONS = {
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/>',
  cloud: '<path d="M7 18h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.6 1.5A3.3 3.3 0 0 0 7 18z"/>',
  rain: '<path d="M7 15h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.6 1.5A3.3 3.3 0 0 0 7 15z"/><path d="M9 18l-1 3M13 18l-1 3M17 18l-1 3"/>',
  snow: '<path d="M7 15h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.6 1.5A3.3 3.3 0 0 0 7 15z"/><path d="M9 19h.01M13 19h.01M17 19h.01M11 22h.01M15 22h.01"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  bookmark: '<path d="M6 3h12v18l-6-4-6 4z"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/>',
  pin: '<path d="M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z"/><circle cx="12" cy="10" r="2.5"/>',
  go: '<path d="M3 11 21 3l-8 18-2-8z"/>',
  back: '<path d="m15 6-6 6 6 6"/>',
  arrow: '<path d="M4 12h15m-6-6 6 6-6 6"/>',
  check: '<path d="m5 12 5 5 9-10"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  spark: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/>',
  share: '<path d="M12 3v12M7 8l5-5 5 5M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5"/>',
  refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>',
  ticket: '<path d="M4 7h16v3a2 2 0 0 0 0 4v3H4v-3a2 2 0 0 0 0-4z"/><path d="M14 7v10"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18"/>',
  star: '<path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"/>',
  calendar: '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 10h16M9 3v4M15 3v4"/>',
  locate: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><circle cx="12" cy="12" r="7"/>',
  key: '<circle cx="8" cy="15" r="4"/><path d="m11 12 9-9M17 6l3 3M15 8l2 2"/>',
  bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 21h4"/>',
};

export function icon(name, size = 20) {
  return `<svg class="icon" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ICONS.spark}</svg>`;
}

export function hydrateIcons(root = document) {
  for (const el of $$('[data-icon]', root)) el.innerHTML = icon(el.dataset.icon, 22);
}

// ---- Distance -------------------------------------------------------------------------------

export function miles(a, b) {
  const r = Math.PI / 180, dLat = (b.lat - a.lat) * r, dLng = (b.lng - a.lng) * r;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLng / 2) ** 2;
  return 7917.6 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

const REGION = (navigator.language || 'en-US').split('-')[1] || 'US';
export const USES_MILES = ['US', 'GB', 'LR', 'MM'].includes(REGION.toUpperCase());

export function distanceLabel(mi) {
  if (!Number.isFinite(mi)) return '';
  if (USES_MILES) return mi < 0.1 ? 'Right here' : `${mi < 10 ? mi.toFixed(1) : Math.round(mi)} mi`;
  const km = mi * 1.609;
  return km < 0.15 ? 'Right here' : km < 1 ? `${Math.round(km * 100) * 10} m` : `${km < 10 ? km.toFixed(1) : Math.round(km)} km`;
}

/** "8 min walk" up to a mile, then a rough drive time. */
export function travelLabel(mi) {
  if (!Number.isFinite(mi)) return '';
  if (mi <= 1) return `${Math.max(2, Math.round(mi * 20))} min walk`;
  return `${Math.round(mi * 2 + 4)} min drive`;
}

// ---- Time -----------------------------------------------------------------------------------

export function timeLabel(date) {
  const h = date.getHours(), m = date.getMinutes();
  const hh = h % 12 || 12, ap = h < 12 ? 'am' : 'pm';
  return m ? `${hh}:${String(m).padStart(2, '0')} ${ap}` : `${hh} ${ap}`;
}

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export function dayLabel(date, now = new Date()) {
  const start = d => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const diff = Math.round((start(date) - start(now)) / 86400000);
  if (diff === 0) return date.getHours() >= 17 ? 'Tonight' : 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff < 7) return DAYS[date.getDay()];
  return `${DAYS[date.getDay()]} ${MONTHS[date.getMonth()]} ${date.getDate()}`;
}

export function eventWhen(e) {
  const d = new Date(e.start);
  const day = dayLabel(d);
  const when = e.localTime ? timeLabel(d) : '';
  return day + (when ? ` · ${when}` : '');
}

export function nowLabel(now = new Date()) {
  const day = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'][now.getDay()];
  return `${day} · ${timeLabel(now).toUpperCase()}`;
}

const TINTS = { coffee: 't-warm', sweet: 't-warm', food: 't-clay', drinks: 't-clay', nightlife: 't-night', outdoors: 't-green',
  trails: 't-green', views: 't-sky', gardens: 't-green', culture: 't-plum', books: 't-plum', music: 't-night', quiet: 't-warm' };

// ---- Photos ---------------------------------------------------------------------------------

export function photoSrc(item, width = 400, index = 0) {
  if (item?.type === 'event') return item.image || '';
  const ref = item?.photos?.[index]?.ref;
  return ref ? `/api/photo?ref=${encodeURIComponent(ref)}&w=${width}` : '';
}

/** An <img> that fades in when loaded and falls back to a soft placeholder. */
export function photo(item, width, cls = '', alt = '') {
  const src = photoSrc(item, width);
  const fallback = `<span class="ph-fallback">${esc((item?.name || '?').slice(0, 1))}</span>`;
  const tint = TINTS[item?.tags?.[0]] || '';
  if (!src) return `<span class="ph ${cls} ph-empty ${tint}">${fallback}</span>`;
  return `<span class="ph ${cls} ${tint}">${fallback}<img src="${esc(src)}" alt="${esc(alt)}" loading="lazy" decoding="async" data-fade></span>`;
}

/** Fade images in once loaded; keep the letter placeholder if they fail. */
export function watchImages(root) {
  for (const img of $$('img[data-fade]', root)) {
    const done = () => img.classList.add('in');
    if (img.complete && img.naturalWidth) done();
    else {
      img.addEventListener('load', done, { once: true });
      img.addEventListener('error', () => img.remove(), { once: true });
    }
  }
}

// ---- Toast ----------------------------------------------------------------------------------

let toastTimer;
export function toast(message) {
  const el = $('#toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 3200);
}

export function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}
