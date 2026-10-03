# Roam for Android (0.8.0)

Roam is a travel companion: a native Android shell (location, calendar, notifications, background
work) around the Roam web app in `assets/`. This is a source rebuild of the 0.7.0 test APK with the
fixes below. The web app is the same one, with small changes.

## What changed since 0.7.0

**Installing and updates**
- Release build signed with your own key instead of the shared Android debug key. Keep
  `roam-release.jks` and its password safe: every future update must be signed with it.
- No longer `debuggable`: faster, and other apps or a USB cable can't inspect its data.

**Battery**
- The departure companion used GPS and network location every 15 s all day. It now switches
  between ACTIVE (precise fixes every 15 s, while moving or confirming a departure) and RESTING
  (low-power fixes at most every 2 min, only after moving 50 m, while you stay put). A low-power
  fix well away from where you settled wakes precise tracking for 3 minutes. See `LocationPlan`.
- Uses Android's fused location provider on Android 12+.
- The departure detector no longer throws away the place you're at after 15 minutes without a fix
  (which RESTING mode makes normal); only gaps over 2 hours start fresh.

**Works outside Colorado**
- The phone's time zone replaces hard-coded `America/Denver` for "today", seasons, quiet-hour
  day boundaries, calendar defaults and trip/plan timing. Red Rocks event times stay in Mountain Time.
- Distances in notifications use km or miles depending on your region.

**Your data**
- Backups are on: Android backs up your preferences and saved data with your Google account.
- New "Your data" section on the My taste page: **Save a backup** writes a JSON file wherever you
  choose; **Restore from a file** brings it back (with confirmation, and it rolls back if the
  restore fails partway).

**Public map services**
- Removed the fallback to the OpenStreetMap *editing* API, which isn't meant for apps like this.
  A second public Overpass server is tried instead.
- Overpass results are cached on the phone for 30 minutes per area. Address search follows
  Nominatim's usage policy: at most one request per second, and repeated searches are answered
  from memory.

**Polish**
- Adaptive app icon (sharp on every phone, supports themed icons) replaces a single low-res PNG.
- After a reboot, the "resume" notification uses Roam's icon and clears once the companion runs.
- Turning on the companion asks for notification permission first on Android 13+.
- Edge-to-edge layout handled for Android 15, including the keyboard.
- Rotating the phone no longer reloads the app; Back on the home screen leaves Roam running.
- The app restarts cleanly if Android kills the WebView renderer, instead of crashing.
- Removed 4 duplicate photos.

**Code**
- The native code is rewritten as readable source. Selection logic (`DepartureDetector`,
  `LocationPlan`, `Suggestions`, `Planner`, policies) is plain Java with 30 unit tests.

## Building

```sh
sudo apt install aapt apksigner zipalign dalvik-exchange   # plus a JDK 17+
ROAM_KEYSTORE=/path/to/roam-release.jks ROAM_KEYSTORE_PASS=... ./build.sh
```

`build.sh` downloads two Android framework jars and the test libraries from Maven Central on the
first run, runs the unit tests, then compiles, packages, aligns and signs `build/roam-<version>.apk`.
When you change the version, update both `versionName`/`versionCode` in `AndroidManifest.xml` and
`BuildInfo.VERSION`.

## Layout

| Path | What it is |
| --- | --- |
| `src/app/roam/companion/` | Native code. `MainActivity` hosts the WebView, `WebBridge` is `window.RoamAndroid`, `AssetServer` serves `assets/` and the `/api/*` endpoints. |
| `CompanionService`, `LocationPlan`, `DepartureDetector` | Departure-aware companion and its battery plan. |
| `PlanningJob`, `Planner` | Background "near home" / "before your trip" ideas. |
| `assets/` | The web app (unchanged structure; `backup.js` is new). |
| `test/` | JVM unit tests for the pure logic. |
