package app.roam.companion;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One curated pick a day. It's chosen once (at your pick time, or when you first open Roam that
 * day), kept for the whole day so it doesn't reshuffle, and sent as a notification with a photo.
 */
public final class DailyPick extends BroadcastReceiver {
    static final String ACTION = "app.roam.companion.DAILY_PICK";
    static final int NOTIFICATION_ID = 205;
    static final int MAX_ANOTHER = 2;

    @Override
    public void onReceive(final Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject pick = today(c, false);
                    if (pick != null && !pick.has("error")) post(c, pick);
                } catch (Exception ignored) {
                    // try again tomorrow
                } finally {
                    schedule(c);
                    result.finish();
                }
            }
        }, "roam-daily-pick").start();
    }

    private static String key(LocalDate d) {
        return "pick:" + d;
    }

    /** Today's pick, choosing it if needed. `another` swaps it for the next best (twice a day at most). */
    static synchronized JSONObject today(Context c, boolean another) throws JSONException {
        return today(c, another, false);
    }

    /** `fresh` re-chooses for a new location (after travelling) without counting as a swap. */
    static synchronized JSONObject today(Context c, boolean another, boolean fresh) throws JSONException {
        LocalDate date = LocalDate.now();
        String saved = NativeStore.prefs(c).getString(key(date), null);
        JSONObject current = saved == null ? null : Json.object(saved);
        if (current != null && !another && !fresh) return current;
        int swaps = current == null ? 0 : current.optInt("swaps") + (another ? 1 : 0);
        if (fresh) current = null;
        if (current != null && current.optInt("swaps") >= MAX_ANOTHER) return current.put("notice", "That's all the picks for today. More tomorrow.");

        JSONObject snapshot = NativeStore.snapshot(c);
        JSONObject loc = snapshot.optJSONObject("location");
        if (loc == null || !loc.has("lat")) loc = NativeStore.location(c);
        if (!loc.has("lat")) return HomeSearch.error("Roam needs to know where you are first.");
        double lat = loc.optDouble("lat"), lng = loc.optDouble("lng");

        JSONObject placesResult = Places.nearby(c, lat, lng, false);
        JSONArray places = placesResult.optJSONArray("places");
        if (places == null) places = new JSONArray();
        JSONArray events = Events.fetch(c, lat, lng).optJSONArray("events");
        JSONObject weather = Weather.fetch(c, lat, lng);

        Set<String> exclude = new HashSet<String>();
        JSONArray ex = snapshot.optJSONArray("exclude");
        for (int i = 0; ex != null && i < ex.length(); i++) exclude.add(ex.optString(i));
        JSONArray history = history(c);
        List<String> recentKinds = new ArrayList<String>();
        for (int i = history.length() - 1; i >= 0; i--) {
            JSONObject h = history.optJSONObject(i);
            if (h == null) continue;
            if (LocalDate.parse(h.optString("date")).isAfter(date.minusDays(60))) exclude.add(h.optString("id"));
            recentKinds.add(h.optString("kind"));
        }
        if (current != null) {
            exclude.add(current.optJSONObject("item").optString("id"));
            JSONArray alts = current.optJSONArray("alternates");
            for (int i = 0; alts != null && i < alts.length(); i++) {
                if (i == 0) continue; // the first alternate is a fine next pick
                exclude.add(alts.optJSONObject(i).optJSONObject("item").optString("id"));
            }
        }

        Curator.Moment m = new Curator.Moment(pickTime(snapshot, date), weather.has("error") ? null : weather);
        JSONObject origin = new JSONObject().put("lat", lat).put("lng", lng);
        List<JSONObject> list = Curator.shortlist(places, events, snapshot.optJSONObject("weights"),
                snapshot.optDouble("distance", 5), origin, exclude, recentKinds, m, 6);
        if (list.isEmpty()) {
            return HomeSearch.error(Keys.has(c, Keys.GOOGLE)
                    ? "Nothing nearby clears Roam's bar right now. Try another area or check back tomorrow."
                    : "Roam's picks need Google Places for ratings and reviews. Add a key in You › Connections.");
        }

        // Read reviews only for the top few; that's where the specific reasons come from.
        List<JSONObject> written = new ArrayList<JSONObject>();
        for (int i = 0; i < list.size() && written.size() < 3; i++) {
            JSONObject item = list.get(i);
            JSONObject details = null;
            String id = item.optString("id");
            if (id.startsWith("g:")) {
                try {
                    details = GooglePlaces.details(c, id.substring(2));
                } catch (Exception ignored) {
                    // write it without reviews
                }
            }
            JSONObject words = Curator.compose(item, details, m, origin, snapshot.optJSONObject("weights"), snapshot.optJSONObject("evidence"));
            written.add(new JSONObject().put("item", clean(item)).put("words", words));
        }

        JSONObject pick = written.get(0);
        JSONArray alternates = new JSONArray();
        for (int i = 1; i < written.size(); i++) alternates.put(written.get(i));
        JSONObject out = new JSONObject()
                .put("date", date.toString())
                .put("item", pick.getJSONObject("item"))
                .put("words", pick.getJSONObject("words"))
                .put("alternates", alternates)
                .put("swaps", swaps)
                .put("madeAt", System.currentTimeMillis());
        NativeStore.prefs(c).edit().putString(key(date), out.toString()).remove(key(date.minusDays(2))).apply();
        remember(c, pick.getJSONObject("item"), date);
        return out;
    }

    private static JSONObject clean(JSONObject item) throws JSONException {
        JSONObject copy = new JSONObject(item.toString());
        copy.remove("_score");
        return copy;
    }

    static ZonedDateTime pickTime(JSONObject snapshot, LocalDate date) {
        JSONObject daily = snapshot.optJSONObject("daily");
        LocalTime t;
        try {
            t = LocalTime.parse(daily == null ? "16:30" : daily.optString("time", "16:30"));
        } catch (Exception e) {
            t = LocalTime.of(16, 30);
        }
        ZonedDateTime at = date.atTime(t).atZone(java.time.ZoneId.systemDefault());
        ZonedDateTime now = ZonedDateTime.now();
        return now.isAfter(at) ? now : at;
    }

    static JSONArray history(Context c) {
        return Json.array(NativeStore.prefs(c).getString("pickHistory", "[]"));
    }

    private static void remember(Context c, JSONObject item, LocalDate date) throws JSONException {
        JSONArray h = history(c);
        h.put(new JSONObject().put("id", item.optString("id")).put("kind", Curator.kindOf(item)).put("date", date.toString()));
        while (h.length() > 120) h.remove(0);
        NativeStore.prefs(c).edit().putString("pickHistory", h.toString()).apply();
    }

    // ---- Notification -------------------------------------------------------------------------

    static void post(Context c, JSONObject pick) {
        JSONObject item = pick.optJSONObject("item");
        JSONObject words = pick.optJSONObject("words");
        if (item == null || words == null) return;
        String title = "Today's pick: " + item.optString("name");
        String body = words.optString("headline") + (words.optString("practical").isEmpty() ? "" : ". " + words.optString("practical"));
        Bitmap image = picture(c, item);
        NativeNotifications.channels(c);
        if (!NativeNotifications.enabled(c)) return;
        Intent open = new Intent(c, MainActivity.class).putExtra("view", "pick")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, NativeNotifications.SUGGESTIONS)
                .setSmallIcon(R.drawable.ic_roam)
                .setColor(0xFFB5502C)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Into it", NativeNotifications.reply(c, item.optString("id"), "yes", false)).build())
                .addAction(new Notification.Action.Builder(null, "Not for me", NativeNotifications.reply(c, item.optString("id"), "no", false)).build());
        if (image != null) {
            b.setLargeIcon(image).setStyle(new Notification.BigPictureStyle().bigPicture(image).setSummaryText(body));
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(body));
        }
        try {
            NativeNotifications.manager(c).notify(NOTIFICATION_ID, b.build());
            NativeStore.prefs(c).edit().putBoolean("sent:" + LocalDate.now(), true)
                    .remove("sent:" + LocalDate.now().minusDays(1)).apply();
        } catch (SecurityException ignored) {
            // notifications switched off
        }
    }

    private static Bitmap picture(Context c, JSONObject item) {
        try {
            byte[] bytes = null;
            JSONArray photos = item.optJSONArray("photos");
            if (photos != null && photos.length() > 0) {
                bytes = new PhotoCache(c).get(photos.getJSONObject(0).optString("ref"), 800);
            } else if (item.optString("image").startsWith("https://")) {
                String host = new java.net.URL(item.optString("image")).getHost();
                if (host.endsWith(".ticketm.net") || host.endsWith(".ticketmaster.com")) {
                    Http.Response r = Http.exchange("GET", item.optString("image"),
                            java.util.Collections.singletonMap("Accept", "image/*"), null, null, 6_000_000);
                    if (r.ok()) bytes = r.body;
                }
            }
            return bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Alarm --------------------------------------------------------------------------------

    /** Next pick time from the latest snapshot (inexact alarm, no special permission). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, NOTIFICATION_ID,
                new Intent(c, DailyPick.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        JSONObject daily = NativeStore.snapshot(c).optJSONObject("daily");
        if (daily != null && !daily.optBoolean("enabled", true)) {
            am.cancel(pi);
            return;
        }
        LocalTime t;
        try {
            t = LocalTime.parse(daily == null ? "16:30" : daily.optString("time", "16:30"));
        } catch (Exception e) {
            t = LocalTime.of(16, 30);
        }
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime at = now.toLocalDate().atTime(t).atZone(now.getZone());
        // Today's slot if it's still ahead and today's pick hasn't been sent; otherwise tomorrow.
        if (!at.isAfter(now.plusMinutes(1)) || NativeStore.prefs(c).getBoolean("sent:" + now.toLocalDate(), false)) at = at.plusDays(1);
        am.setWindow(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), 15 * 60_000L, pi);
    }
}
