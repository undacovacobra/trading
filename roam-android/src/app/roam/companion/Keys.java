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
        prefs(c).edit().putString(which, v).apply();
    }

    private static String bucket(String kind) {
        return "used:" + kind + ":" + YearMonth.now();
    }

    static int used(Context c, String kind) {
        return prefs(c).getInt(bucket(kind), 0);
    }

    static int budget(String kind) {
        return "photo".equals(kind) ? PHOTO_BUDGET : SEARCH_BUDGET;
    }

    /** Reserves one call from this month's budget; false when the budget is spent. */
    static synchronized boolean spend(Context c, String kind) {
        int n = used(c, kind);
        if (n >= budget(kind)) return false;
        prefs(c).edit().putInt(bucket(kind), n + 1).apply();
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
