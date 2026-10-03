// The phone connection (window.RoamAndroid). In a desktop browser a stand-in keeps the app usable.

const real = window.RoamAndroid;

const devKeys = () => { try { return JSON.parse(localStorage.getItem('roam-dev-keys') || '{}'); } catch { return {}; } };

const stand_in = {
  status: () => JSON.stringify({
    version: 'dev', tracking: false, trackingWanted: false, precise: true, approximate: true, locationEnabled: true,
    notifications: true, replies: [], location: {}, weeklyLast: {},
    calendar: !!localStorage.getItem('roam-dev-calendar'), calendarPermission: !!localStorage.getItem('roam-dev-calendar'),
    keys: { google: !!devKeys().google, ticketmaster: !!devKeys().ticketmaster, googleHint: '', ticketmasterHint: '',
      searchUsed: 0, searchBudget: 900, photoUsed: 0, photoBudget: 900 },
  }),
  snapshot: () => {},
  setKey: (which, value) => localStorage.setItem('roam-dev-keys', JSON.stringify({ ...devKeys(), [which]: value })),
  request: what => {
    if (what === 'calendar') { localStorage.setItem('roam-dev-calendar', '[]'); setTimeout(() => window.roamNativeCalendarReady?.(), 50); return; }
    if (what !== 'current') return;
    navigator.geolocation?.getCurrentPosition(
      p => window.roamNativeFix?.({ lat: p.coords.latitude, lng: p.coords.longitude, accuracy: p.coords.accuracy, at: Date.now() }),
      () => window.roamNativeFix?.({ error: 'Location unavailable. Search for a place instead.' }));
  },
  // In a browser, pretend a calendar is connected when localStorage 'roam-dev-calendar' holds entries.
  calendar: () => {
    try {
      const records = JSON.parse(localStorage.getItem('roam-dev-calendar') || 'null');
      return JSON.stringify(records ? { status: 'connected', records } : { status: 'needs-permission' });
    } catch { return '{"status":"error"}'; }
  },
  calendarOff: () => {},
  ack: () => {},
  stopTracking: () => {},
  openURL: url => window.open(url, '_blank', 'noopener'),
  directions: (lat, lng) => window.open(`https://www.google.com/maps/dir/?api=1&destination=${lat}%2C${lng}`, '_blank', 'noopener'),
  share: text => navigator.share ? navigator.share({ text }).catch(() => {}) : navigator.clipboard?.writeText(text),
  openSettings: () => {},
  saveFile: (name, text) => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  },
};

export const phone = real || stand_in;
export const isPhone = !!real;

export function status() {
  try { return JSON.parse(phone.status()); } catch { return {}; }
}
