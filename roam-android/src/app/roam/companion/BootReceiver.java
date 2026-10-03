package app.roam.companion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores the weekly picks alarm after a reboot or update. Android doesn't let an app restart
 * "while in use" location tracking from the background, so the companion asks you to tap once.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        WeeklyReceiver.schedule(c);
        if (NativeStore.trackingWanted(c) && !CompanionService.active) NativeNotifications.resumeReminder(c);
    }
}
