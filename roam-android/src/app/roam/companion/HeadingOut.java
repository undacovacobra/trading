package app.roam.companion;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * "Heading out?": right after you leave somewhere you spent a while, look at what's close to where
 * you are now and send one notification only if something clears the daily pick's bar, is open,
 * fits what you like, and isn't the same kind of place you just left. Silence is the default.
 */
final class HeadingOut {
    static final long LOOK_SPACING_MS = 30 * 60_000L;  // at most one search per half hour
    static final long RENUDGE_MS = 21 * 86_400_000L;   // never suggest the same place twice in 3 weeks
    static final float DRIVING_MPS = 5f;               // ~18 km/h

    private HeadingOut() {}

    /** Called off the main thread right after a confirmed departure. */
    static void consider(Context c, double lat, double lng, float speedMps, double leftLat, double leftLng, long stayMs) {
        try {
            run(c, lat, lng, speedMps, leftLat, leftLng, stayMs);
        } catch (Exception ignored) {
            // never crash the location service over a suggestion
        }
    }

    private static void run(Context c, double lat, double lng, float speedMps, double leftLat, double leftLng, long stayMs)
            throws JSONException {
        long now = System.currentTimeMillis();
        ZonedDateTime local = ZonedDateTime.now();
        boolean fromHome = learnHome(c, leftLat, leftLng, now, stayMs, local.getZone());

        JSONObject snapshot = NativeStore.snapshot(c);
        JSONObject departure = snapshot.optJSONObject("departure");
        if (departure == null || !departure.optBoolean("enabled")) return;
        // Leaving home is a commute, not an outing.
        if (fromHome) return;
        JSONObject quiet = snapshot.optJSONObject("quiet");
        boolean isQuiet = quiet != null && NudgePolicy.quiet(quiet.optString("start"), quiet.optString("end"), local.toLocalTime());
        boolean busy = Suggestions.busyIn(snapshot.optJSONArray("busy"), now);
        if (!NudgePolicy.allowed(snapshot.optInt("max", 2), NativeStore.sentToday(c), isQuiet, busy,
                NativeStore.prefs(c).getLong("lastNudge", 0), now)) {
            return;
        }
        if (now - NativeStore.prefs(c).getLong("lastLook", 0) < LOOK_SPACING_MS) return;
        NativeStore.prefs(c).edit().putLong("lastLook", now).apply();

        // Reuses cached results within ~1.5 miles; a new area costs one capped Google search.
        JSONArray places = Places.nearby(c, lat, lng, false).optJSONArray("places");
        if (places == null || places.length() == 0) return;
        JSONObject weather = Weather.fetch(c, lat, lng);

        Set<String> exclude = new HashSet<String>();
        JSONArray ex = snapshot.optJSONArray("exclude");
        for (int i = 0; ex != null && i < ex.length(); i++) exclude.add(ex.optString(i));
        JSONArray replies = NativeStore.replies(c);
        for (int i = 0; i < places.length(); i++) {
            String id = places.optJSONObject(i).optString("id");
            if (Suggestions.declined(replies, id, now)) exclude.add(id);
        }
        JSONArray nudged = nudged(c);
        for (int i = 0; i < nudged.length(); i++) {
            JSONObject n = nudged.optJSONObject(i);
            if (n != null && now - n.optLong("at") < RENUDGE_MS) exclude.add(n.optString("id"));
        }

        String favorId = null;
        JSONObject pick = Json.object(NativeStore.prefs(c).getString("pick:" + LocalDate.now(), "{}"));
        if (pick.optJSONObject("item") != null) favorId = pick.optJSONObject("item").optString("id");

        boolean driving = speedMps >= DRIVING_MPS;
        JSONObject here = new JSONObject().put("lat", lat).put("lng", lng);
        JSONObject left = Double.isFinite(leftLat) ? new JSONObject().put("lat", leftLat).put("lng", leftLng) : null;
        Curator.Moment m = new Curator.Moment(local, weather.has("error") ? null : weather);
        List<JSONObject> list = Curator.afterLeaving(places, snapshot.optJSONObject("weights"), here, left,
                exclude, m, driving, favorId, 1);
        if (list.isEmpty()) return;

        JSONObject item = new JSONObject(list.get(0).toString());
        JSONObject details = null;
        if (item.optString("id").startsWith("g:")) {
            try {
                details = GooglePlaces.details(c, item.optString("id").substring(2));
            } catch (Exception ignored) {
                // write it without reviews
            }
        }
        JSONObject words = Curator.compose(item, details, m, here, snapshot.optJSONObject("weights"), snapshot.optJSONObject("evidence"));
        item.remove("_score");
        String travel = Curator.travel(Curator.meters(here, item), driving);
        boolean isPick = item.optString("id").equals(favorId);
        String title = isPick ? "Today's pick is a " + travel + " away" : item.optString("name") + " · " + travel;
        String body = isPick ? item.optString("name") + ". " + words.optString("headline") : words.optString("headline");
        int open = Curator.openFor(item, local);
        if (open > 0 && open < 24 * 60) body += ". Open until " + Curator.timeLabel(local.plusMinutes(open).toLocalTime());
        if (post(c, item, title, body, driving)) {
            NativeStore.recordDelivery(c, now);
            remember(c, item, now);
        }
    }

    // ---- Home: where you spend the night, learned, never asked --------------------------------

    private static boolean learnHome(Context c, double lat, double lng, long now, long stayMs, ZoneId zone) throws JSONException {
        JSONArray spots = Json.array(NativeStore.prefs(c).getString("overnights", "[]"));
        boolean night = Home.overnight(now, stayMs, zone);
        boolean home = Home.after(spots, lat, lng, night);
        if (night) NativeStore.prefs(c).edit().putString("overnights", spots.toString()).apply();
        return home;
    }

    // ---- History and notification -------------------------------------------------------------

    static JSONArray nudged(Context c) {
        return Json.array(NativeStore.prefs(c).getString("nudged", "[]"));
    }

    private static void remember(Context c, JSONObject item, long now) throws JSONException {
        JSONArray list = nudged(c);
        list.put(new JSONObject().put("id", item.optString("id")).put("at", now).put("item", item));
        while (list.length() > 20) list.remove(0);
        NativeStore.prefs(c).edit().putString("nudged", list.toString()).apply();
    }

    private static boolean post(Context c, JSONObject item, String title, String body, boolean driving) {
        NativeNotifications.channels(c);
        if (!NativeNotifications.enabled(c)) return false;
        String id = item.optString("id");
        Bitmap image = DailyPick.picture(c, item);
        Notification.Builder b = new Notification.Builder(c, NativeNotifications.SUGGESTIONS)
                .setSmallIcon(R.drawable.ic_roam)
                .setColor(0xFFB5502C)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setTimeoutAfter(90 * 60_000L)  // a "right now" idea goes stale
                .setContentIntent(NativeNotifications.open(c, id))
                .addAction(new Notification.Action.Builder(null, "Directions", directions(c, item, driving)).build())
                .addAction(new Notification.Action.Builder(null, "Not now", NativeNotifications.reply(c, id, "later", false)).build())
                .addAction(new Notification.Action.Builder(null, "Not my thing", NativeNotifications.reply(c, id, "no", false)).build());
        if (image != null) {
            b.setLargeIcon(image).setStyle(new Notification.BigPictureStyle().bigPicture(image).setSummaryText(body));
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(body));
        }
        try {
            NativeNotifications.manager(c).notify(id.hashCode(), b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Opens directions in Google Maps (or whatever maps app handles the link). */
    private static PendingIntent directions(Context c, JSONObject item, boolean driving) {
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination="
                + item.optDouble("lat") + "%2C" + item.optDouble("lng") + "&travelmode=" + (driving ? "driving" : "walking")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, (item.optString("id") + "go").hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
