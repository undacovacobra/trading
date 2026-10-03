package app.roam.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class NativeNotifications {
    static final String SUGGESTIONS = "companion";
    static final String TRACKING = "journey";
    static final int TRACKING_ID = 1;
    static final int RESUME_ID = 204;

    private NativeNotifications() {}

    static NotificationManager manager(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void channels(Context c) {
        NotificationManager m = manager(c);
        NotificationChannel suggestions = new NotificationChannel(SUGGESTIONS, "Companion suggestions",
                NotificationManager.IMPORTANCE_DEFAULT);
        suggestions.setDescription("Places and events suggested when you head out or plan a trip.");
        m.createNotificationChannel(suggestions);
        NotificationChannel tracking = new NotificationChannel(TRACKING, "Location companion is active",
                NotificationManager.IMPORTANCE_LOW);
        tracking.setDescription("Shown while Roam watches for you leaving a place.");
        tracking.setShowBadge(false);
        m.createNotificationChannel(tracking);
    }

    /** Notifications are on for the app and the suggestions channel isn't muted. */
    static boolean enabled(Context c) {
        NotificationManager m = manager(c);
        if (!m.areNotificationsEnabled()) return false;
        NotificationChannel ch = m.getNotificationChannel(SUGGESTIONS);
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static PendingIntent open(Context c, String placeId) {
        Intent i = new Intent(c, MainActivity.class)
                .putExtra("placeId", placeId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, placeId.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent reply(Context c, String placeId, String action, boolean mutable) {
        Intent i = new Intent(c, ReplyReceiver.class).setAction(action).putExtra("placeId", placeId);
        // A RemoteInput reply needs a mutable PendingIntent on Android 12+.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (mutable && Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getBroadcast(c, (placeId + action).hashCode(), i, flags);
    }

    static boolean suggestion(Context c, String placeId, String title, String body) {
        channels(c);
        if (!enabled(c)) return false;
        Notification.Builder b = new Notification.Builder(c, SUGGESTIONS)
                .setSmallIcon(R.drawable.ic_roam)
                .setColor(0xFFB5502C)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(open(c, placeId));
        b.addAction(new Notification.Action.Builder(null, "Sounds good", reply(c, placeId, "yes", false)).build());
        b.addAction(new Notification.Action.Builder(null, "Not now", reply(c, placeId, "later", false)).build());
        b.addAction(new Notification.Action.Builder(null, "Tell me how it feels", reply(c, placeId, "text", true))
                .addRemoteInput(new RemoteInput.Builder("reply").setLabel("How does this suggestion feel?").build())
                .build());
        try {
            manager(c).notify(placeId.hashCode(), b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    static Notification tracking(Context c, boolean resting) {
        channels(c);
        PendingIntent pause = PendingIntent.getBroadcast(c, 99,
                new Intent(c, ReplyReceiver.class).setAction(ReplyReceiver.STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, TRACKING)
                .setSmallIcon(R.drawable.ic_roam)
                .setColor(0xFFB5502C)
                .setContentTitle("Your companion is keeping an eye out")
                .setContentText(resting
                        ? "Resting while you stay put. It wakes up when you head out."
                        : "Looking for thoughtful stops when you leave a place.")
                .setContentIntent(open(c, ""))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Pause", pause).build())
                .build();
    }

    static void resumeReminder(Context c) {
        channels(c);
        if (!manager(c).areNotificationsEnabled()) return;
        Notification n = new Notification.Builder(c, SUGGESTIONS)
                .setSmallIcon(R.drawable.ic_roam)
                .setColor(0xFFB5502C)
                .setContentTitle("Resume your travel companion")
                .setContentText("Tap to turn departure suggestions back on after your phone restarted.")
                .setContentIntent(PendingIntent.getActivity(c, RESUME_ID, new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build();
        try {
            manager(c).notify(RESUME_ID, n);
        } catch (SecurityException ignored) {
            // notification permission was withdrawn
        }
    }
}
