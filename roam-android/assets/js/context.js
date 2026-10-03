// What kind of moment it is: time of day, weather and sunset shape the headline and the ranking.

export function context(weather, date = new Date()) {
  const h = date.getHours() + date.getMinutes() / 60;
  const part = h >= 5 && h < 11 ? 'morning' : h < 14 && h >= 11 ? 'midday' : h >= 14 && h < 17 ? 'afternoon' : h >= 17 && h < 21.5 ? 'evening' : 'late';
  const sky = weather?.sky || null;
  const f = weather?.tempF;
  const wet = ['rain', 'snow', 'storm'].includes(sky);
  const cold = Number.isFinite(f) && f < 40;
  const hot = Number.isFinite(f) && f >= 88;
  const nice = !wet && !cold && !hot && Number.isFinite(f) && f >= 55;
  const sunsetIn = weather?.sunset ? (weather.sunset - date.getTime()) / 60000 : null;
  const sunsetSoon = sunsetIn !== null && sunsetIn > 0 && sunsetIn < 100 && !wet;

  const boosts = {
    morning: { coffee: 1.5, sweet: 1, trails: 0.8, outdoors: 0.5 },
    midday: { food: 1.5, culture: 0.8, outdoors: 0.5, gardens: 0.5 },
    afternoon: { coffee: 1, culture: 1, books: 1, outdoors: 0.8, views: 0.8, gardens: 0.8, sweet: 0.5 },
    evening: { food: 1.5, drinks: 1.2, music: 1, views: sunsetSoon ? 1.5 : 0 },
    late: { drinks: 1.5, nightlife: 1.5, food: 0.5, outdoors: -2, trails: -3, gardens: -3, culture: -1 },
  }[part];
  const b = { ...boosts };
  const add = (tags, n) => { for (const t of tags) b[t] = (b[t] || 0) + n; };
  if (wet || cold || hot) { add(['outdoors', 'trails', 'views', 'gardens'], -2); add(['coffee', 'culture', 'books', 'food', 'quiet'], 0.7); }
  if (nice && part !== 'late') add(['outdoors', 'trails', 'views', 'gardens'], 0.8);
  if (part === 'morning') add(['nightlife', 'drinks'], -3);

  return { date, part, sky, tempF: f, wet, cold, hot, nice, sunsetSoon, sunset: weather?.sunset || null, boosts: b, headline: headline(part, { wet, cold, hot, nice, sky, sunsetSoon, sunset: weather?.sunset }) };
}

function headline(part, w) {
  const sunset = w.sunset ? new Date(w.sunset).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '';
  if (w.cold && part !== 'late') return 'Cold out. Somewhere warm and good, then.';
  if (w.hot && part !== 'late' && part !== 'evening') return 'Hot out. Somewhere cool, shady or air-conditioned?';
  if (w.wet) return part === 'evening' || part === 'late' ? 'A cozy night in town, then.' : 'Rainy out. Good for books, tea and museums.';
  switch (part) {
    case 'morning': return w.nice ? 'A bright start. Coffee first, then somewhere green?' : 'Ease into the day with something warm.';
    case 'midday': return 'Lunch, or a long wander?';
    case 'afternoon': return w.nice ? 'Good light for an easy walk, or a slow coffee.' : 'An easy afternoon. Somewhere to linger?';
    case 'evening': return w.sunsetSoon && sunset ? `Sunset's at ${sunset}. Find a view, then dinner.` : 'Dinner, and maybe something after?';
    default: return 'Still out? Here’s what’s good late.';
  }
}

export function skyIcon(weather, isDay = true) {
  const sky = weather?.sky;
  if (sky === 'rain' || sky === 'storm') return 'rain';
  if (sky === 'snow') return 'snow';
  if (sky === 'cloudy' || sky === 'fog') return 'cloud';
  return weather?.isDay === false || !isDay ? 'moon' : 'sun';
}
