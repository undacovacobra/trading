package app.roam.companion;

import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The departure-aware companion. Runs as a location foreground service while Roam is "on",
 * and suggests a nearby place shortly after you leave somewhere you stayed for a while.
 *
 * Battery: instead of asking GPS and network for a fix every 15 seconds all day, it switches
 * between ACTIVE (precise, while moving) and RESTING (low power, while you stay put); see
 * {@link LocationPlan}. On Android 12+ it uses the system's fused location provider.
 */
public final class CompanionService extends Service implements LocationListener {
    static volatile boolean active;

    private final DepartureDetector detector = new DepartureDetector();
    private final LocationPlan plan = new LocationPlan();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private LocationPlan.Mode mode;

    @Override
    public void onCreate() {
        super.onCreate();
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NativeNotifications.TRACKING_ID, NativeNotifications.tracking(this, false),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NativeNotifications.TRACKING_ID, NativeNotifications.tracking(this, false));
            }
            active = true;
            NativeStore.prefs(this).edit().putBoolean("tracking", true).apply();
            NativeNotifications.manager(this).cancel(NativeNotifications.RESUME_ID);
            mode = null;
            apply(LocationPlan.Mode.ACTIVE);
        } catch (RuntimeException e) {
            // Android refuses a location service started from the background without background
            // permission; the app restarts it the next time it's opened.
            NativeStore.prefs(this).edit().putBoolean("tracking", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /** Switches location requests to match the plan; does nothing if the mode is unchanged. */
    private void apply(LocationPlan.Mode next) {
        if (next == mode) return;
        mode = next;
        manager.removeUpdates(this);
        boolean resting = next == LocationPlan.Mode.RESTING;
        long interval = resting ? LocationPlan.RESTING_INTERVAL_MS : LocationPlan.ACTIVE_INTERVAL_MS;
        float distance = resting ? LocationPlan.RESTING_DISTANCE_M : LocationPlan.ACTIVE_DISTANCE_M;
        try {
            if (Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
                LocationRequest request = new LocationRequest.Builder(interval)
                        .setQuality(resting ? LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                                : LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMinUpdateDistanceMeters(distance)
                        .setMinUpdateIntervalMillis(interval / 2)
                        .build();
                manager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, getMainExecutor(), this);
            } else {
                // Older phones: cell/wifi location while resting, GPS (plus network) while active.
                boolean requested = false;
                for (String provider : resting
                        ? new String[] {LocationManager.NETWORK_PROVIDER}
                        : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    if (manager.getAllProviders().contains(provider)) {
                        manager.requestLocationUpdates(provider, interval, distance, this, Looper.getMainLooper());
                        requested = true;
                    }
                }
                if (!requested && manager.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, distance, this, Looper.getMainLooper());
                }
            }
        } catch (SecurityException e) {
            stopSelf();
            return;
        }
        NativeNotifications.manager(this).notify(NativeNotifications.TRACKING_ID, NativeNotifications.tracking(this, resting));
    }

    @Override
    public void onLocationChanged(Location fix) {
        long ageNanos = SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos();
        if (ageNanos > 120_000_000_000L || !fix.hasAccuracy()) return;
        long at = fix.getElapsedRealtimeNanos() / 1_000_000L;
        NativeStore.saveLocation(this, fix.getLatitude(), fix.getLongitude(), fix.getAccuracy());

        if (mode == LocationPlan.Mode.RESTING
                && plan.wakeFor(detector, fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(), at)) {
            apply(LocationPlan.Mode.ACTIVE);
        }
        boolean departed = detector.update(fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(), at);
        if (departed) considerDeparture(fix);
        apply(plan.mode(detector, at));
    }

    private void considerDeparture(final Location fix) {
        final double leftLat = detector.departedLat();
        final double leftLng = detector.departedLng();
        // Calendar reads and JSON work happen off the main thread.
        background.execute(new Runnable() {
            @Override
            public void run() {
                suggestAfterDeparture(fix.getLatitude(), fix.getLongitude(), leftLat, leftLng);
            }
        });
    }

    private void suggestAfterDeparture(double lat, double lng, double leftLat, double leftLng) {
        JSONObject snapshot = NativeStore.snapshot(this);
        JSONObject departure = snapshot.optJSONObject("departure");
        if (departure == null || !departure.optBoolean("enabled")) return;
        long now = System.currentTimeMillis();
        ZonedDateTime local = ZonedDateTime.now();
        JSONObject quiet = snapshot.optJSONObject("quiet");
        boolean isQuiet = quiet != null && NudgePolicy.quiet(quiet.optString("start"), quiet.optString("end"), local.toLocalTime());
        boolean busy = Suggestions.busyIn(snapshot.optJSONArray("busy"), now);
        if (!NudgePolicy.allowed(snapshot.optInt("max", 1), NativeStore.sentToday(this), isQuiet, busy,
                NativeStore.prefs(this).getLong("lastNudge", 0), now)) {
            return;
        }
        JSONArray candidates = Suggestions.departureCandidates(snapshot);
        JSONObject best = Suggestions.bestAfterDeparture(snapshot, candidates, NativeStore.replies(this),
                lat, lng, leftLat, leftLng, now, local);
        if (best == null) return;
        double meters = DepartureDetector.meters(lat, lng, best.optDouble("lat"), best.optDouble("lng"));
        String body = "Heading out? " + best.optString("reason", "A place you might enjoy") + " · about "
                + Units.distance(meters, Locale.getDefault()) + " away. Check hours and the route before going.";
        String id = best.optString("id");
        if (NativeNotifications.suggestion(this, id, best.optString("name"), body)) {
            NativeStore.recordDelivery(this, now);
            NativeStore.reply(this, id, "suggestion", "");
        }
    }

    @Override
    public void onDestroy() {
        active = false;
        background.shutdownNow();
        if (manager != null) manager.removeUpdates(this);
        NativeStore.prefs(this).edit().putBoolean("tracking", false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Required on Android 8-10, where LocationListener has no default methods.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}
}
