package app.roam.companion;

import java.time.ZonedDateTime;
import java.util.List;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The weekly picks notification, at the day and time you choose. Uses an inexact alarm (no special
 * permission, kind to the battery); Android may deliver it a few minutes late.
 */
public final class WeeklyReceiver extends BroadcastReceiver {
    static final String ACTION = "app.roam.companion.WEEKLY";
    static final int NOTIFICATION_ID = 205;

    @Override
    public void onReceive(final Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    post(c);
                } catch (Exception ignored) {
                    // try again next week
                } finally {
                    schedule(c);
                    result.finish();
                }
            }
        }, "roam-weekly").start();
    }

    static void post(Context c) throws Exception {
        JSONObject snapshot = NativeStore.snapshot(c);
        JSONObject weekly = snapshot.optJSONObject("weekly");
        if (weekly == null || !weekly.optBoolean("enabled")) return;
        long now = System.currentTimeMillis();
        List<JSONObject> picks = WeeklyPicks.choose(snapshot.optJSONArray("picks"), now, 3);
        if (picks.isEmpty()) return;
        ZonedDateTime local = ZonedDateTime.now();
        String body = WeeklyPicks.message(picks, local);

        Bitmap image = null;
        for (JSONObject p : picks) {
            image = picture(c, p);
            if (image != null) break;
        }
        NativeNotifications.channels(c);
        Intent open = new Intent(c, MainActivity.class).putExtra("view", "picks")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent dismiss = PendingIntent.getBroadcast(c, NOTIFICATION_ID,
                new Intent(c, ReplyReceiver.class).setAction(ReplyReceiver.DISMISS_WEEKLY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, NativeNotifications.SUGGESTIONS)
                .setSmallIcon(R.drawable.ic_roam)
                .setColor(0xFFB5502C)
                .setContentTitle(WeeklyPicks.title(local))
                .setContentText(body)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Open picks", openPi).build())
                .addAction(new Notification.Action.Builder(null, "Not this week", dismiss).build());
        if (image != null) {
            b.setLargeIcon(image).setStyle(new Notification.BigPictureStyle().bigPicture(image).setSummaryText(body));
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(body));
        }
        if (!NativeNotifications.enabled(c)) return;
        NativeNotifications.manager(c).notify(NOTIFICATION_ID, b.build());

        JSONArray ids = new JSONArray();
        for (JSONObject p : picks) ids.put(p.optString("id"));
        NativeStore.prefs(c).edit().putString("weeklyLast",
                new JSONObject().put("at", now).put("ids", ids).toString()).apply();
    }

    /** A photo for the notification: a cached place photo, or the event's image. */
    private static Bitmap picture(Context c, JSONObject p) {
        try {
            byte[] bytes = null;
            String ref = p.optString("photo");
            if (ref.startsWith("g:") || ref.startsWith("c:")) {
                bytes = new PhotoCache(c).get(ref, 800);
            } else if (ref.startsWith("https://")) {
                String host = new java.net.URL(ref).getHost();
                if (host.endsWith(".ticketm.net") || host.endsWith(".ticketmaster.com")) {
                    Http.Response r = Http.exchange("GET", ref, java.util.Collections.singletonMap("Accept", "image/*"),
                            null, null, 6_000_000);
                    if (r.ok()) bytes = r.body;
                }
            }
            return bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    /** Sets (or clears) the next weekly alarm from the latest snapshot. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, NOTIFICATION_ID,
                new Intent(c, WeeklyReceiver.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        JSONObject weekly = NativeStore.snapshot(c).optJSONObject("weekly");
        if (weekly == null || !weekly.optBoolean("enabled")) {
            am.cancel(pi);
            return;
        }
        ZonedDateTime at = WeeklyPicks.next(ZonedDateTime.now(), weekly.optInt("day", 5), weekly.optString("time", "17:00"));
        am.setWindow(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), 15 * 60_000L, pi);
    }
}
