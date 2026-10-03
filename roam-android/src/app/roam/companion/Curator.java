package app.roam.companion;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Chooses the one daily pick, by rules rather than AI:
 * - a quality bar (well rated by enough people, or a high-rated hidden gem; no chains or fast food),
 * - fit with what you like, the day and the hour, and variety from recent picks,
 * - a reason built from specific facts: what reviewers keep mentioning, Google's own description,
 *   being the best-rated of its kind nearby, an event tonight.
 */
final class Curator {
    static final double MI = 1609.344;

    private static final List<String> CHAINS = Arrays.asList(
            "starbucks", "dunkin", "mcdonald", "burger king", "wendy", "taco bell", "subway", "chipotle", "panera",
            "chick-fil-a", "chick fil a", "kfc", "domino", "pizza hut", "papa john", "little caesar", "olive garden",
            "applebee", "chili's", "red robin", "buffalo wild wings", "ihop", "denny", "cracker barrel", "texas roadhouse",
            "outback", "panda express", "five guys", "jimmy john", "jersey mike", "noodles & company", "qdoba",
            "dutch bros", "tim hortons", "costa coffee", "caribou coffee", "peet's", "krispy kreme", "cold stone",
            "dairy queen", "sonic drive", "arby", "popeyes", "jack in the box", "carl's jr", "whataburger", "in-n-out",
            "shake shack", "smashburger", "culver", "raising cane", "wingstop", "p.f. chang", "cheesecake factory",
            "hooters", "twin peaks", "barnes & noble", "dave & buster", "topgolf", "main event", "round1", "bowlero");

    private static final Set<String> NOT_PICKS = new HashSet<String>(Arrays.asList(
            "fast_food_restaurant", "meal_takeaway", "meal_delivery", "convenience_store", "gas_station",
            "supermarket", "grocery_store", "department_store", "shopping_mall", "pharmacy", "food_court"));

    private static final Set<String> OUTDOOR = new HashSet<String>(Arrays.asList("outdoors", "trails", "views", "gardens"));

    static final Map<String, String[]> TAG_WORDS = new HashMap<String, String[]>();

    static {
        // label, plural
        TAG_WORDS.put("coffee", new String[] {"coffee & tea", "coffee spots"});
        TAG_WORDS.put("quiet", new String[] {"quiet corners", "quiet places"});
        TAG_WORDS.put("sweet", new String[] {"something sweet", "bakeries"});
        TAG_WORDS.put("food", new String[] {"good food", "places to eat"});
        TAG_WORDS.put("drinks", new String[] {"good drinks", "bars"});
        TAG_WORDS.put("nightlife", new String[] {"nights out", "night spots"});
        TAG_WORDS.put("outdoors", new String[] {"fresh air", "outdoor spots"});
        TAG_WORDS.put("trails", new String[] {"easy trails", "trails"});
        TAG_WORDS.put("views", new String[] {"big views", "viewpoints"});
        TAG_WORDS.put("gardens", new String[] {"gardens", "gardens"});
        TAG_WORDS.put("culture", new String[] {"art & culture", "museums and galleries"});
        TAG_WORDS.put("books", new String[] {"bookshops", "bookshops"});
        TAG_WORDS.put("sights", new String[] {"local sights", "sights"});
        TAG_WORDS.put("fun", new String[] {"fun & games", "fun spots"});
        TAG_WORDS.put("music", new String[] {"live music", "live shows"});
        TAG_WORDS.put("sports", new String[] {"live sports", "games"});
        TAG_WORDS.put("wellness", new String[] {"unwinding", "spas"});
        TAG_WORDS.put("active", new String[] {"getting moving", "active spots"});
    }

    private Curator() {}

    // ---- Quality bar --------------------------------------------------------------------------

    static String normName(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[’']", "'");
        int cut = n.indexOf(" - ");
        if (cut > 0) n = n.substring(0, cut);
        return n.replaceAll("[^a-z0-9& ']", " ").replaceAll("\\s+", " ").trim();
    }

    static Map<String, Integer> nameCounts(JSONArray places) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (int i = 0; i < places.length(); i++) {
            JSONObject p = places.optJSONObject(i);
            if (p == null) continue;
            String n = normName(p.optString("name"));
            counts.put(n, counts.containsKey(n) ? counts.get(n) + 1 : 1);
        }
        return counts;
    }

    static boolean chain(JSONObject p, Map<String, Integer> counts) {
        String n = normName(p.optString("name"));
        for (String c : CHAINS) if (n.contains(c)) return true;
        Integer seen = counts.get(n);
        return seen != null && seen >= 2;
    }

    /** A rating pulled toward 4.1 when few people have rated it, so 5.0 from 6 reviews doesn't win. */
    static double bayes(JSONObject p) {
        double r = p.optDouble("rating", 0);
        int n = p.optInt("ratings", 0);
        return (r * n + 4.1 * 40) / (n + 40);
    }

    static boolean worthIt(JSONObject p, Map<String, Integer> counts) {
        if ("event".equals(p.optString("type"))) return true;
        if (chain(p, counts)) return false;
        JSONArray types = p.optJSONArray("types");
        if (types != null) for (int i = 0; i < types.length(); i++) if (NOT_PICKS.contains(types.optString(i))) return false;
        if ("osm".equals(p.optString("source"))) return p.optBoolean("notable");
        double r = p.optDouble("rating", 0);
        int n = p.optInt("ratings", 0);
        return (r >= 4.5 && n >= 80) || (r >= 4.7 && n >= 25);
    }

    static boolean hiddenGem(JSONObject p) {
        return p.optDouble("rating", 0) >= 4.7 && p.optInt("ratings") >= 25 && p.optInt("ratings") < 300;
    }

    /** Best-rated place of the same kind within 10 miles (with at least 100 ratings)? */
    static boolean bestOfKind(JSONObject p, JSONArray places, Map<String, Integer> counts) {
        if (p.optInt("ratings") < 100 || p.optString("kind").isEmpty()) return false;
        double mine = bayes(p);
        int rivals = 0;
        for (int i = 0; i < places.length(); i++) {
            JSONObject q = places.optJSONObject(i);
            if (q == null || q == p || !p.optString("kind").equals(q.optString("kind"))) continue;
            if (meters(p, q) > 10 * MI || chain(q, counts)) continue;
            rivals++;
            if (bayes(q) > mine) return false;
        }
        return rivals >= 2;
    }

    // ---- The moment ---------------------------------------------------------------------------

    static final class Moment {
        final ZonedDateTime at;
        final boolean weekend;
        final String part;
        final boolean badWeather;
        final long sunset;

        Moment(ZonedDateTime at, JSONObject weather) {
            this.at = at;
            int dow = at.getDayOfWeek().getValue();
            int h = at.getHour();
            this.weekend = dow == 6 || dow == 7 || (dow == 5 && h >= 15);
            this.part = h < 11 ? "morning" : h < 15 ? "midday" : h < 21 ? "evening" : "late";
            String sky = weather == null ? "" : weather.optString("sky");
            long f = weather == null ? 70 : weather.optLong("tempF", 70);
            this.badWeather = sky.equals("rain") || sky.equals("snow") || sky.equals("storm") || f < 40 || f >= 90;
            this.sunset = weather == null ? 0 : weather.optLong("sunset");
        }

        /** How long the pick is "for": the rest of today on weekdays, the weekend on weekends. */
        long horizonEnd() {
            ZonedDateTime end = at.toLocalDate().plusDays(1).atStartOfDay(at.getZone()).plusHours(3);
            if (weekend) {
                int dow = at.getDayOfWeek().getValue();
                end = at.toLocalDate().plusDays(8 - dow).atStartOfDay(at.getZone());
            }
            return end.toInstant().toEpochMilli();
        }

        double boost(String tag) {
            double b = 0;
            if (part.equals("morning")) b += in(tag, "coffee", "sweet", "trails", "gardens", "views") ? 1.2 : in(tag, "nightlife", "drinks") ? -3 : 0;
            if (part.equals("midday")) b += in(tag, "food", "culture", "gardens", "sights") ? 1 : 0;
            if (part.equals("evening")) b += in(tag, "food", "drinks", "music") ? 1.3 : in(tag, "coffee", "sweet") ? -0.5 : 0;
            if (part.equals("late")) b += in(tag, "drinks", "nightlife", "music") ? 1.5 : OUTDOOR.contains(tag) ? -3 : 0;
            if (weekend && OUTDOOR.contains(tag) && !part.equals("late")) b += 0.8;
            if (badWeather && OUTDOOR.contains(tag)) b -= 2.5;
            if (badWeather && in(tag, "coffee", "culture", "books", "food", "quiet")) b += 0.6;
            return b;
        }
    }

    private static boolean in(String tag, String... options) {
        for (String o : options) if (o.equals(tag)) return true;
        return false;
    }

    // ---- Opening hours (Google periods, day 0 = Sunday) ---------------------------------------

    /** Minutes the place stays open after `at`, 0 if closed then, -1 if hours are unknown. */
    static int openFor(JSONObject p, ZonedDateTime at) {
        JSONObject hours = p.optJSONObject("hours");
        JSONArray periods = hours == null ? null : hours.optJSONArray("periods");
        if (periods == null || periods.length() == 0) return -1;
        if (periods.length() == 1 && periods.optJSONObject(0).optJSONObject("close") == null) return 24 * 60;
        int week = 7 * 1440;
        int now = (at.getDayOfWeek().getValue() % 7) * 1440 + at.getHour() * 60 + at.getMinute();
        for (int i = 0; i < periods.length(); i++) {
            JSONObject per = periods.optJSONObject(i);
            JSONObject o = per == null ? null : per.optJSONObject("open");
            JSONObject c = per == null ? null : per.optJSONObject("close");
            if (o == null || c == null) continue;
            int start = o.optInt("day") * 1440 + o.optInt("hour") * 60 + o.optInt("minute");
            int end = c.optInt("day") * 1440 + c.optInt("hour") * 60 + c.optInt("minute");
            if (end <= start) end += week;
            for (int shift : new int[] {0, -week}) {
                if (now >= start + shift && now < end + shift) return end + shift - now;
            }
        }
        return 0;
    }

    /** Open at some point in the next few hours from `at`. */
    static boolean openSoon(JSONObject p, ZonedDateTime at) {
        for (int h = 0; h <= 3; h++) if (openFor(p, at.plusHours(h)) != 0) return true;
        return false;
    }

    // ---- Ranking ------------------------------------------------------------------------------

    static double meters(JSONObject a, JSONObject b) {
        return DepartureDetector.meters(a.optDouble("lat"), a.optDouble("lng"), b.optDouble("lat"), b.optDouble("lng"));
    }

    static double taste(JSONArray tags, JSONObject weights) {
        if (tags == null || tags.length() == 0 || weights == null) return 0;
        double sum = 0;
        for (int i = 0; i < tags.length(); i++) sum += Math.max(-6, Math.min(6, weights.optDouble(tags.optString(i), 0)));
        return sum / Math.sqrt(tags.length());
    }

    static String kindOf(JSONObject item) {
        JSONArray tags = item.optJSONArray("tags");
        String t = tags != null && tags.length() > 0 ? tags.optString(0) : "other";
        return "event".equals(item.optString("type")) ? "event:" + t : t;
    }

    /**
     * The best candidates for today, best first, each with "_score". `exclude` holds places you
     * passed on, went to recently, or were picked in the last 60 days; `recentKinds` the kinds
     * of the last few picks (newest first).
     */
    static List<JSONObject> shortlist(JSONArray places, JSONArray events, JSONObject weights, double distanceMiles,
                                      JSONObject origin, Set<String> exclude, List<String> recentKinds,
                                      Moment m, int n) throws JSONException {
        Map<String, Integer> counts = nameCounts(places);
        List<JSONObject> out = new ArrayList<JSONObject>();
        double tol = Math.max(1, distanceMiles);
        for (int i = 0; i < places.length(); i++) {
            JSONObject p = places.optJSONObject(i);
            if (p == null || exclude.contains(p.optString("id")) || !worthIt(p, counts)) continue;
            if (!openSoon(p, m.at)) continue;
            JSONArray tags = p.optJSONArray("tags");
            boolean outdoor = false;
            for (int t = 0; tags != null && t < tags.length(); t++) outdoor |= OUTDOOR.contains(tags.optString(t));
            double miles = meters(origin, p) / MI;
            double reach = tol * (outdoor ? 3 : 1.5);
            if (miles > Math.min(reach, 40)) continue;
            double s = 1.5 * taste(tags, weights) - 1.4 * miles / (tol * (outdoor ? 3 : 1));
            s += (bayes(p) - 4.3) * 3 + Math.min(Math.log10(p.optInt("ratings") + 1), 4) * 0.4;
            if (bestOfKind(p, places, counts)) { s += 1.2; p.put("_best", true); }
            if (hiddenGem(p)) { s += 0.8; p.put("_gem", true); }
            if (p.optBoolean("notable")) s += 0.6;
            double boost = 0;
            for (int t = 0; tags != null && t < tags.length(); t++) boost += m.boost(tags.optString(t));
            s += Math.max(-4, Math.min(2.5, boost));
            s += variety(kindOf(p), recentKinds);
            out.add(p.put("_score", s));
        }
        long horizon = m.horizonEnd();
        long now = m.at.toInstant().toEpochMilli();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || exclude.contains(e.optString("id"))) continue;
            long start = e.optLong("start");
            if (start < now + 30 * 60_000L || start > horizon) continue;
            double miles = meters(origin, e) / MI;
            double t = taste(e.optJSONArray("tags"), weights);
            if (miles > 50 || t < 0) continue;
            double s = 1.5 * t + 1.5 - miles / (tol * 4) + (start - now < 30 * 3_600_000L ? 1 : 0) + variety(kindOf(e), recentKinds);
            out.add(e.put("_score", s));
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(b.optDouble("_score"), a.optDouble("_score"));
            }
        });
        return out.size() > n ? new ArrayList<JSONObject>(out.subList(0, n)) : out;
    }

    // ---- Right after you leave somewhere ------------------------------------------------------

    static final double WALK_REACH_M = 1300;   // about a 15 minute walk
    static final double DRIVE_REACH_M = 8000;  // about a 10-15 minute drive
    static final double AWAY_FROM_LEFT_M = 150;

    /** The kind of place you were just at (the closest known place within 75 m), or null. */
    static String kindNear(JSONArray places, JSONObject at) {
        String kind = null;
        double best = 75;
        for (int i = 0; places != null && i < places.length(); i++) {
            JSONObject p = places.optJSONObject(i);
            if (p == null) continue;
            double d = meters(at, p);
            if (d < best) { best = d; kind = kindOf(p); }
        }
        return kind;
    }

    /**
     * What's worth a stop right now, close to where you are, after leaving `left`. Stricter than
     * the daily pick: it must already be open (for at least 45 more minutes), close by for how
     * you're moving, something you've shown you like, and not the same kind of place you just left.
     * `favorId` (today's pick) wins if it's close and open.
     */
    static List<JSONObject> afterLeaving(JSONArray places, JSONObject weights, JSONObject here, JSONObject left,
                                         Set<String> exclude, Moment m, boolean driving, String favorId, int n)
            throws JSONException {
        Map<String, Integer> counts = nameCounts(places);
        String leftKind = left == null ? null : kindNear(places, left);
        long now = m.at.toInstant().toEpochMilli();
        boolean dark = m.sunset > 0 ? now > m.sunset - 20 * 60_000L : m.part.equals("late");
        double reach = driving ? DRIVE_REACH_M : WALK_REACH_M;
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; i < places.length(); i++) {
            JSONObject p = places.optJSONObject(i);
            if (p == null || exclude.contains(p.optString("id")) || !worthIt(p, counts)) continue;
            double d = meters(here, p);
            if (!(d <= reach)) continue;
            if (left != null && meters(left, p) < AWAY_FROM_LEFT_M) continue;
            boolean favored = p.optString("id").equals(favorId);
            if (!favored && leftKind != null && leftKind.equals(kindOf(p))) continue;
            JSONArray tags = p.optJSONArray("tags");
            boolean outdoor = false;
            for (int t = 0; tags != null && t < tags.length(); t++) outdoor |= OUTDOOR.contains(tags.optString(t));
            if (outdoor && (dark || m.badWeather)) continue;
            int open = openFor(p, m.at);
            if (open == 0 || (open > 0 && open < 45) || (open < 0 && !outdoor)) continue;
            double boost = 0;
            for (int t = 0; tags != null && t < tags.length(); t++) boost += m.boost(tags.optString(t));
            if (boost <= -2) continue;  // bars at breakfast time, trails at midnight
            double t = taste(tags, weights);
            boolean best = bestOfKind(p, places, counts);
            boolean gem = hiddenGem(p);
            // "You'd probably enjoy it": real evidence you like this kind, or a standout you don't dislike.
            if (!favored && !(t >= 0.8 || (t >= 0 && (best || gem)))) continue;
            double s = 1.6 * t + (bayes(p) - 4.3) * 3 + Math.max(-2, Math.min(2.5, boost)) - d / (driving ? 4000 : 600);
            if (best) { s += 1.0; p.put("_best", true); }
            if (gem) { s += 0.6; p.put("_gem", true); }
            if (favored) s += 100;  // already judged the best thing today
            out.add(p.put("_score", s));
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(b.optDouble("_score"), a.optDouble("_score"));
            }
        });
        return out.size() > n ? new ArrayList<JSONObject>(out.subList(0, n)) : out;
    }

    /** "6 min walk" / "12 min drive", the same estimate the pick card uses. */
    static String travel(double meters, boolean driving) {
        double miles = meters / MI;
        if (!driving && miles <= 1.2) return Math.max(2, Math.round(miles * 20)) + " min walk";
        return Math.max(3, Math.round(miles * 2 + 3)) + " min drive";
    }

    static double variety(String kind, List<String> recent) {
        for (int i = 0; i < recent.size() && i < 5; i++) {
            if (recent.get(i).equals(kind)) return i < 2 ? -2.5 : -1;
        }
        return 0;
    }

    // ---- What reviewers keep mentioning -------------------------------------------------------

    private static final Set<String> STOP = new HashSet<String>(Arrays.asList((
            "a an the and or but if so of to in on at for from by with without about as is are was were be been being "
            + "it its it's this that these those there here i me my we our you your they them their he she his her "
            + "very really so too just also even still always never ever quite pretty super much many more most "
            + "great good nice amazing awesome excellent best better love loved lovely wonderful fantastic perfect "
            + "delicious tasty yummy cool fun favorite favourite place spot location area time times day visit visited "
            + "came come go went get got back again will would could should can definitely highly recommend recommended "
            + "staff service friendly helpful people experience everything thing things lot little bit one two "
            + "food drink drinks menu order ordered price prices worth well made make not no all some had have has "
            + "do did does what when where which who how than then out up down over only other another first new old "
            + "town city boulder denver here's i've we've they're you're don't didn't isn't wasn't can't won't").split(" ")));

    private static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        for (String w : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ").split("\\s+")) {
            String t = w.replaceAll("^'+|'+$", "");
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    /** Phrases at least two different reviews mention ("green chile", "patio"), best first. */
    static List<String> highlights(JSONArray reviews, int max) {
        Map<String, Integer> pairs = new LinkedHashMap<String, Integer>();
        Map<String, Integer> singles = new LinkedHashMap<String, Integer>();
        if (reviews == null) return new ArrayList<String>();
        for (int i = 0; i < reviews.length(); i++) {
            JSONObject r = reviews.optJSONObject(i);
            if (r == null || r.optInt("rating", 5) < 4) continue;
            List<String> w = words(r.optString("text"));
            Set<String> seenPairs = new HashSet<String>();
            Set<String> seenSingles = new HashSet<String>();
            for (int k = 0; k < w.size(); k++) {
                String a = w.get(k);
                if (STOP.contains(a)) continue;
                if (seenSingles.add(a)) singles.put(a, singles.containsKey(a) ? singles.get(a) + 1 : 1);
                if (k + 1 < w.size() && !STOP.contains(w.get(k + 1))) {
                    String pair = a + " " + w.get(k + 1);
                    if (seenPairs.add(pair)) pairs.put(pair, pairs.containsKey(pair) ? pairs.get(pair) + 1 : 1);
                }
            }
        }
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : sortByCount(pairs)) if (e.getValue() >= 2 && out.size() < max) out.add(e.getKey());
        for (Map.Entry<String, Integer> e : sortByCount(singles)) {
            if (out.size() >= max || e.getValue() < 2 || e.getKey().length() < 4 || e.getKey().endsWith("ly")) continue;
            boolean covered = false;
            for (String o : out) covered |= o.contains(e.getKey());
            if (!covered) out.add(e.getKey());
        }
        return out;
    }

    private static List<Map.Entry<String, Integer>> sortByCount(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> list = new ArrayList<Map.Entry<String, Integer>>(m.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        return list;
    }

    /** A short sentence from a 5-star review, preferably one that mentions a highlight. */
    static JSONObject quote(JSONArray reviews, List<String> highlights) throws JSONException {
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; reviews != null && i < reviews.length(); i++) {
            JSONObject r = reviews.optJSONObject(i);
            if (r == null || r.optInt("rating") < 5) continue;
            for (String sentence : r.optString("text").split("(?<=[.!?])\\s+|\\n+")) {
                String s = sentence.trim();
                if (s.length() < 35 || s.length() > 150 || s.contains("http")) continue;
                int score = 1;
                for (String h : highlights) if (s.toLowerCase(Locale.ROOT).contains(h)) score += 3;
                if (score > bestScore) {
                    bestScore = score;
                    best = new JSONObject().put("text", s).put("by", r.optString("by"));
                }
            }
        }
        return best;
    }

    // ---- Writing the pick ---------------------------------------------------------------------

    static String timeLabel(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        String ap = t.getHour() < 12 ? "am" : "pm";
        return t.getMinute() == 0 ? h + " " + ap : String.format(Locale.US, "%d:%02d %s", h, t.getMinute(), ap);
    }

    static String whenLabel(long start, ZonedDateTime now) {
        ZonedDateTime s = Instant.ofEpochMilli(start).atZone(now.getZone());
        long days = s.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay();
        String time = timeLabel(s.toLocalTime());
        if (days == 0) return (s.getHour() >= 17 ? "tonight" : "today") + " at " + time;
        if (days == 1) return "tomorrow at " + time;
        return "on " + s.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US) + " at " + time;
    }

    /**
     * The words around a pick: a headline, an "about" line, up to two reasons, the things people
     * mention, a review quote and a practical line. Everything comes from data Roam has.
     */
    static JSONObject compose(JSONObject item, JSONObject details, Moment m, JSONObject origin,
                              JSONObject weights, JSONObject evidence) throws JSONException {
        JSONObject out = new JSONObject();
        boolean event = "event".equals(item.optString("type"));
        JSONArray reviews = details == null ? null : details.optJSONArray("reviews");
        List<String> hl = highlights(reviews, 3);
        String kindLabel = item.optString("kind", "").toLowerCase(Locale.ROOT);
        double miles = meters(origin, item) / MI;
        String headline;
        if (event) {
            boolean music = Json.contains(item.optJSONArray("tags"), "music");
            headline = item.optString("name") + (music ? " plays " : " at ") + item.optString("venue", "nearby") + " " + whenLabel(item.optLong("start"), m.at);
        } else if (!hl.isEmpty() && hl.get(0).contains(" ")) {
            headline = (Math.abs(item.optString("id").hashCode()) % 2 == 0 ? "Everyone who goes mentions the " : "Reviewers keep coming back to the ") + hl.get(0);
        } else if (item.optBoolean("_best")) {
            headline = "The best-rated " + (kindLabel.isEmpty() ? "spot" : kindLabel) + " within 10 miles";
        } else if (item.optBoolean("_gem")) {
            headline = String.format(Locale.US, "A %.1f-star %s most people haven't found yet", item.optDouble("rating"), kindLabel.isEmpty() ? "spot" : kindLabel);
        } else if (!hl.isEmpty()) {
            headline = "People can't stop talking about the " + hl.get(0);
        } else if (item.has("rating")) {
            headline = String.format(Locale.US, "%.1f stars from %,d people, and right up your alley", item.optDouble("rating"), item.optInt("ratings"));
        } else {
            headline = "Worth the trip today";
        }
        out.put("headline", headline);
        if (details != null && !details.optString("summary").isEmpty()) out.put("about", details.optString("summary"));

        JSONArray why = new JSONArray();
        String personal = personal(item, weights, evidence);
        if (personal != null) why.put(personal);
        if (!event && item.optBoolean("_best") && !headline.startsWith("The best-rated")) {
            why.put("It's the best-rated " + (kindLabel.isEmpty() ? "spot of its kind" : kindLabel) + " within 10 miles.");
        } else if (!event && item.has("rating") && !headline.contains("stars")) {
            why.put(String.format(Locale.US, "Rated %.1f by %,d people on Google.", item.optDouble("rating"), item.optInt("ratings")));
        }
        out.put("why", why);
        out.put("highlights", new JSONArray(hl));
        JSONObject q = quote(reviews, hl);
        if (q != null) out.put("quote", q);

        List<String> practical = new ArrayList<String>();
        if (!event) {
            int open = openFor(item, m.at);
            if (open > 0 && open < 24 * 60) practical.add("Open until " + timeLabel(m.at.plusMinutes(open).toLocalTime()));
            else if (open == 0) practical.add("Opens a little later today");
        }
        practical.add(miles <= 1 ? Math.max(2, Math.round(miles * 20)) + " min walk" : Math.round(miles * 2 + 4) + " min drive");
        if (item.has("price") && item.optInt("price") > 0) practical.add(new String(new char[item.optInt("price")]).replace("\0", "$"));
        if (!event && Json.contains(item.optJSONArray("tags"), "views") && m.sunset > m.at.toInstant().toEpochMilli()) {
            practical.add("sunset " + timeLabel(Instant.ofEpochMilli(m.sunset).atZone(m.at.getZone()).toLocalTime()));
        }
        StringBuilder p = new StringBuilder();
        for (String s : practical) p.append(p.length() == 0 ? "" : " · ").append(s);
        out.put("practical", p.toString());
        return out;
    }

    /** A sentence about you, only when Roam has real evidence for it. */
    static String personal(JSONObject item, JSONObject weights, JSONObject evidence) {
        JSONArray tags = item.optJSONArray("tags");
        String top = null;
        double w = 0.9;
        for (int i = 0; tags != null && i < tags.length(); i++) {
            double v = weights == null ? 0 : weights.optDouble(tags.optString(i), 0);
            if (v > w) { w = v; top = tags.optString(i); }
        }
        if (top == null) return null;
        String[] words = TAG_WORDS.containsKey(top) ? TAG_WORDS.get(top) : new String[] {top, top};
        JSONObject e = evidence == null ? null : evidence.optJSONObject(top);
        int went = e == null ? 0 : e.optInt("went");
        int saved = e == null ? 0 : e.optInt("save");
        if (went >= 2) return "You keep going to " + words[1] + ", and this is one you haven't tried.";
        if (saved >= 2) return "You've saved " + saved + " " + words[1] + ", and this one has the same feel.";
        return "You told Roam you're into " + words[0] + ".";
    }
}
