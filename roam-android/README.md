# Roam for Android (0.10.0)

A travel companion that shows one great thing to do right now, and learns what you like from what
you save, skip and go to. A small native Android shell (location, notifications, caching, API calls)
hosts a lean web interface in `assets/`.

## Screens

- **First minute**: tap a few photo tiles ("Slow coffee", "Big views", …) or skip.
- **Today**: a headline for the moment (time of day, weather, sunset), one main pick with a photo and
  a reason, quick mood filters, upcoming events, and a short "Worth a detour" list that grows on demand.
- **Place / event**: photos, "Why it's for you" (built only from what Roam knows), hours, rating,
  "Pair it with", one-tap feedback (Too far, Not my vibe, Been there, Wrong time), Directions, I went.
- **Explore**: search ("tacos", "climbing gym") or browse by kind; sort by best, nearest, open now.
- **Plans**: a month calendar with trips, plans and busy times from your phone calendar (read
  only, any synced calendar). Tap a day for what's on, events that day and ideas to add. Trips
  have a destination and dates; the trip page shows ideas there and events during your dates, and
  you can put things on specific days. Every place has "Plan it", every event "Add to <day>".
- **Saved**: Want to go / Been.
- **You**: what Roam has learned (and "Bring back" for things it's showing less), weekly picks
  day and time, "Heading out?" ideas, quiet hours, location, API keys and usage, backup and restore.

## How it learns

Each place carries a few tags (coffee, trails, views, culture, …). Roam keeps a weight per tag:
saving +2, going +3, opening a place +0.3, "Not my vibe" −2.5, "Too far" tightens the distance it
suggests. Weights halve every 45 days so it keeps up with you. A place's score combines your tag
weights, Google rating, distance, whether it's open, and the moment (morning coffee, sunset views,
indoor ideas when it rains), and the feed avoids showing five of the same kind in a row.

## Data sources

| What | Source | Key | Cached on the phone |
| --- | --- | --- | --- |
| Places, hours, ratings, photos | Google Places API (New) | Yours, in You › Connections | 24 h per ~4 km area; photos 30 days |
| Places without a key | OpenStreetMap (Overpass, Nominatim) | none | 24 h |
| Events | Ticketmaster Discovery API | Yours | 6 h |
| Weather, sunset | Open-Meteo | none | 30 min |

Google calls are capped at 900 place lookups and 900 photos a month, and 40 lookups and 60 photos
a day (shown in You › Connections). Results are reused for 24 hours anywhere within about 1.5 miles,
the refresh button only goes back to Google when results are over 6 hours old, and when Google
refuses the key Roam waits 6 hours (or until you save a key) before asking again. Past a limit it
serves what it already has, or OpenStreetMap.

## Notifications

- **Weekly picks** (on by default, Fridays 5 pm, changeable): three varied ideas from the ranking
  the app made the last time it was open, with a photo. Inexact alarm; no tracking needed.
- **"Heading out?"** (off by default): the departure-aware location service from 0.8, battery
  friendly, at most one suggestion a day, respecting quiet hours.

## Building

```sh
sudo apt install aapt apksigner zipalign dalvik-exchange   # plus a JDK 17+
ROAM_KEYSTORE=/path/to/roam-release.jks ROAM_KEYSTORE_PASS=... ./build.sh
```

`build.sh` fetches two Android framework jars and the test libraries from Maven Central on first
run, runs the unit tests (logic and the API response parsers), then compiles, packages, aligns and
signs `build/roam-<version>.apk`. Keep the keystore: updates must be signed with the same key.
