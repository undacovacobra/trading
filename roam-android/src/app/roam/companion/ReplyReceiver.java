package app.roam.companion;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Handles the buttons on suggestion notifications and the Pause button on the tracking one. */
public final class ReplyReceiver extends BroadcastReceiver {
    static final String STOP = "stop";
    static final String DISMISS_WEEKLY = "weekly-dismiss";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (STOP.equals(action)) {
            NativeStore.prefs(c).edit().putBoolean("tracking", false).putBoolean("trackingWanted", false).apply();
            c.stopService(new Intent(c, CompanionService.class));
            return;
        }
        if (DISMISS_WEEKLY.equals(action)) {
            NativeNotifications.manager(c).cancel(WeeklyReceiver.NOTIFICATION_ID);
            return;
        }
        String placeId = intent.getStringExtra("placeId");
        if (placeId == null || placeId.isEmpty() || placeId.length() > 100) return;
        String feeling = "yes".equals(action) ? "yes" : "later".equals(action) ? "later" : "no";
        String note = "";
        Bundle input = RemoteInput.getResultsFromIntent(intent);
        if (input != null) {
            CharSequence text = input.getCharSequence("reply");
            if (text != null) note = Json.clip(text.toString(), 600);
        }
        NativeStore.reply(c, placeId, feeling, note);
        NativeNotifications.manager(c).cancel(placeId.hashCode());
    }
}
