package app.roam.companion;

import java.util.Arrays;
import java.util.List;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

/** window.RoamAndroid. Every method validates its input; anything touching the UI hops to the main thread. */
final class WebBridge {
    private static final List<String> REQUESTS = Arrays.asList("tracking", "current", "notifications", "calendar");
    private final MainActivity activity;

    WebBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String status() {
        Context c = activity;
        JSONObject s = new JSONObject();
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            s.put("version", BuildInfo.VERSION);
            s.put("tracking", CompanionService.active);
            s.put("trackingWanted", NativeStore.trackingWanted(c));
            s.put("precise", c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
            s.put("approximate", c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);
            s.put("locationEnabled", Build.VERSION.SDK_INT >= 28 ? lm.isLocationEnabled()
                    : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            s.put("batteryUnrestricted", pm.isIgnoringBatteryOptimizations(c.getPackageName()));
            s.put("notifications", NativeNotifications.enabled(c));
            s.put("replies", NativeStore.replies(c));
            s.put("location", NativeStore.location(c));
            s.put("keys", Keys.status(c));
            s.put("calendar", DeviceCalendar.connected(c));
            s.put("calendarPermission", DeviceCalendar.permitted(c));
            s.put("weeklyLast", Json.object(NativeStore.prefs(c).getString("weeklyLast", "{}")));
        } catch (JSONException ignored) {
            // return what we have
        }
        return s.toString();
    }

    /** The web app's latest rankings and settings, for notifications while Roam is closed. */
    @JavascriptInterface
    public void snapshot(String json) {
        if (json == null || json.length() > 2_000_000) return;
        try {
            new JSONObject(json);
        } catch (JSONException e) {
            return;
        }
        NativeStore.prefs(activity).edit().putString("snapshot", json).apply();
        WeeklyReceiver.schedule(activity);
    }

    @JavascriptInterface
    public void setKey(String which, String value) {
        Keys.set(activity, which, value);
    }

    @JavascriptInterface
    public void request(final String what) {
        if (!REQUESTS.contains(what)) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.enqueueTask(what);
            }
        });
    }

    /** Your phone calendar between two times (ms), read only. */
    @JavascriptInterface
    public String calendar(double from, double to) {
        return DeviceCalendar.read(activity, (long) from, (long) to).toString();
    }

    @JavascriptInterface
    public void calendarOff(boolean off) {
        NativeStore.prefs(activity).edit().putBoolean("calendar", !off).apply();
    }

    @JavascriptInterface
    public void ack(String id) {
        if (id != null) NativeStore.ack(activity, id);
    }

    @JavascriptInterface
    public void stopTracking() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.stopService(new Intent(activity, CompanionService.class));
                NativeStore.prefs(activity).edit().putBoolean("tracking", false).putBoolean("trackingWanted", false).apply();
                activity.refreshUI();
            }
        });
    }

    @JavascriptInterface
    public void openURL(final String url) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.openExternal(url);
            }
        });
    }

    /** Turn-by-turn directions in Google Maps (or whatever maps app you use). */
    @JavascriptInterface
    public void directions(final double lat, final double lng) {
        if (!Places.valid(lat, lng)) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.openExternal("https://www.google.com/maps/dir/?api=1&destination=" + lat + "%2C" + lng);
            }
        });
    }

    @JavascriptInterface
    public void share(final String text) {
        if (text == null || text.length() > 2000) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                activity.startActivity(Intent.createChooser(send, null));
            }
        });
    }

    @JavascriptInterface
    public void openSettings(final String which) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent i;
                if ("location".equals(which)) {
                    i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                } else if ("battery".equals(which)) {
                    i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                } else if ("notifications".equals(which)) {
                    i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
                } else {
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName()));
                }
                try {
                    activity.startActivity(i);
                } catch (RuntimeException e) {
                    activity.message("Could not open those phone settings.");
                }
            }
        });
    }

    /** Saves a backup file wherever you choose (Downloads, Drive, ...). */
    @JavascriptInterface
    public void saveFile(final String name, final String text) {
        if (name == null || text == null || text.length() > 20_000_000) return;
        final String safe = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isEmpty() || safe.length() > 100) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.saveFile(safe, text);
            }
        });
    }
}
