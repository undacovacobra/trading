package app.roam.companion;

import java.time.YearMonth;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Your own API keys (kept only on this phone) and a monthly budget for Google calls, so Roam
 * stops asking Google before the free monthly allowance runs out instead of running up a bill.
 */
final class Keys {
    static final String GOOGLE = "google";
    static final String TICKETMASTER = "ticketmaster";
    /** Budgets per month. Google's free tiers are per SKU; these stay comfortably below them. */
    static final int SEARCH_BUDGET = 900;
    static final int PHOTO_BUDGET = 900;
    /** Reviews and descriptions for the daily pick's shortlist (Google's pricier "Atmosphere" data). */
    static final int DETAILS_BUDGET = 250;
    static final int DETAILS_PER_DAY = 10;
    /** Daily ceilings so one bad day (or a bug) can't burn a month's budget. */
    static final int SEARCH_PER_DAY = 40;
    static final int PHOTO_PER_DAY = 60;
    /** After Google refuses the key, don't ask again for this long (or until a new key is saved). */
    static final long BLOCK_MS = 6 * 3_600_000L;

    private Keys() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("roam-keys", Context.MODE_PRIVATE);
    }

    static String get(Context c, String which) {
        return prefs(c).getString(which, "").trim();
    }

    static boolean has(Context c, String which) {
        return !get(c, which).isEmpty();
    }

    static void set(Context c, String which, String value) {
        if (!GOOGLE.equals(which) && !TICKETMASTER.equals(which)) return;
        String v = value == null ? "" : value.trim();
        if (v.length() > 200 || !v.matches("[A-Za-z0-9_\\-]*")) return;
        SharedPreferences.Editor e = prefs(c).edit().putString(which, v);
        if (GOOGLE.equals(which)) e.remove("blockedUntil").remove("blockedMessage");
        e.apply();
    }

    private static String bucket(String kind) {
        return "used:" + kind + ":" + YearMonth.now();
    }

    private static String dayBucket(String kind) {
        return "day:" + kind + ":" + java.time.LocalDate.now();
    }

    /** Google refused the key: stop asking for a while, and remember why for the settings screen. */
    static void block(Context c, String message) {
        prefs(c).edit().putLong("blockedUntil", System.currentTimeMillis() + BLOCK_MS)
                .putString("blockedMessage", message).apply();
    }

    static boolean blocked(Context c) {
        return prefs(c).getLong("blockedUntil", 0) > System.currentTimeMillis();
    }

    /** Google is usable right now: a key is set and it wasn't refused recently. */
    static boolean googleReady(Context c) {
        return has(c, GOOGLE) && !blocked(c);
    }

    static int used(Context c, String kind) {
        return prefs(c).getInt(bucket(kind), 0);
    }

    static int budget(String kind) {
        return "photo".equals(kind) ? PHOTO_BUDGET : "details".equals(kind) ? DETAILS_BUDGET : SEARCH_BUDGET;
    }

    /** Reserves one call from this month's budget; false when the budget is spent. */
    static synchronized boolean spend(Context c, String kind) {
        int n = used(c, kind);
        int today = prefs(c).getInt(dayBucket(kind), 0);
        int daily = "photo".equals(kind) ? PHOTO_PER_DAY : "details".equals(kind) ? DETAILS_PER_DAY : SEARCH_PER_DAY;
        if (n >= budget(kind) || today >= daily || blocked(c)) return false;
        prefs(c).edit().putInt(bucket(kind), n + 1).putInt(dayBucket(kind), today + 1).apply();
        return true;
    }

    static JSONObject status(Context c) {
        JSONObject s = new JSONObject();
        try {
            s.put("google", has(c, GOOGLE));
            s.put("ticketmaster", has(c, TICKETMASTER));
            s.put("googleHint", hint(get(c, GOOGLE)));
            s.put("ticketmasterHint", hint(get(c, TICKETMASTER)));
            s.put("searchUsed", used(c, "search"));
            s.put("searchBudget", SEARCH_BUDGET);
            s.put("photoUsed", used(c, "photo"));
            s.put("photoBudget", PHOTO_BUDGET);
            s.put("detailsUsed", used(c, "details"));
            s.put("detailsBudget", DETAILS_BUDGET);
            s.put("searchToday", prefs(c).getInt(dayBucket("search"), 0));
            s.put("searchPerDay", SEARCH_PER_DAY);
            if (blocked(c)) s.put("googleProblem", prefs(c).getString("blockedMessage", ""));
        } catch (JSONException ignored) {
            // constant keys
        }
        return s;
    }

    /** Last four characters, so you can tell which key is set without showing it. */
    private static String hint(String key) {
        return key.length() < 8 ? "" : "…" + key.substring(key.length() - 4);
    }
}
