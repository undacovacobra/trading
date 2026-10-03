# Roam for Android (0.11.0)

A travel companion that picks one great thing a day near you, held to a strict quality bar, and learns what you like from what
you save, skip and go to. A small native Android shell (location, notifications, caching, API calls)
hosts a lean web interface in `assets/`.

## Screens

- **First minute**: tap a few photo tiles ("Slow coffee", "Big views", …) or skip.
- **Today**: one curated pick for the day, with a headline built from specifics (what reviewers keep
  mentioning, best-of-kind nearby, a show tonight), a review quote, "People mention" chips and why it
  fits you. "Not for me?" asks what's off and swaps it (twice a day at most). Two alternatives
  below, then today's plans. Nothing else.
- **Place / event**: photos, "Why it's for you" (built only from what Roam knows), hours, rating,
  "Pair it with", one-tap feedback (Too far, Not my vibe, Been there, Wrong time), Directions, I went.
- **Explore**: everything nearby: upcoming events, "Good right now", search ("tacos", "climbing
  gym") and browse by kind; sort by best, nearest, open now.
- **Plans**: a month calendar with trips, plans and busy times from your phone calendar (read
  only, any synced calendar). Tap a day for what's on, events that day and ideas to add. Trips
  have a destination and dates; the trip page shows ideas there and events during your dates, and
  you can put things on specific days. Every place has "Plan it", every event "Add to <day>".
- **Saved**: Want to go / Been.
- **You**: what Roam has learned (and "Bring back" for things it's showing less), daily pick
  on/off and time, "Heading out?" ideas, quiet hours, location, API keys and usage, backup and restore.

## How it learns

Each place carries a few tags (coffee, trails, views, culture, …). Roam keeps a weight per tag:
saving +2, going +3, opening a place +0.3, "Not my vibe" −2.5, "Too far" tightens the distance it
suggests. Weights halve every 45 days so it keeps up with you. A place's score combines your tag
weights, Google rating, distance, whether it's open, and the moment (morning coffee, sunset views,
indoor ideas when it rains), and the feed avoids showing five of the same kind in a row.

## How the daily pick is chosen (rules, no AI)

The pick is made on the phone once a day (at your pick time, or when you first open Roam that day)
and kept all day. A place only qualifies if:

- Google rating ≥ 4.5 with ≥ 80 ratings, or ≥ 4.7 with ≥ 25 (a "hidden gem"). Ratings are
  Bayesian-adjusted so 5.0 from nine people doesn't beat 4.8 from 2,000.
- It isn't a chain (known chain names, or the same name twice nearby) or fast food, gas, a
  pharmacy, a supermarket or similar.
- OpenStreetMap places qualify only if they have a Wikipedia/Wikidata entry.
- It's open at the pick time (or later that evening), and it wasn't your pick in the last 60 days,
  hidden it, been there recently or snoozed it.

Among those, it scores your taste, the moment (sunset views on a clear evening, indoors when it
rains, weekends), best-of-kind within 10 miles, and variety (yesterday's kind drops down). For the
top three it reads Google reviews (cached 14 days, at most 10 a day and 250 a month) and pulls
phrases that two or more happy reviewers mention, plus the best quote.

## Data sources

| What | Source | Key | Cached on the phone |
| --- | --- | --- | --- |
| Places, hours, ratings, photos | Google Places API (New) | Yours, in You › Connections | 24 h per ~4 km area; photos 30 days |
| Places without a key | OpenStreetMap (Overpass, Nominatim) | none | 24 h |
| Events | Ticketmaster Discovery API | Yours | 6 h |
| Weather, sunset | Open-Meteo | none | 30 min |

Google calls are capped at 900 place lookups, 900 photos and 250 review lookups a month, and 40
lookups, 60 photos and 10 review lookups a day (shown in You › Connections). Results are reused for 24 hours anywhere within about 1.5 miles,
the refresh button only goes back to Google when results are over 6 hours old, and when Google
refuses the key Roam waits 6 hours (or until you save a key) before asking again. Past a limit it
serves what it already has, or OpenStreetMap.

## Notifications

- **Daily pick** (on by default, 4:30 pm, changeable): today's pick with its photo, "Into it" and
  "Not for me" buttons. Inexact alarm; no tracking needed. Replaces 0.9–0.10's weekly digest.
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
