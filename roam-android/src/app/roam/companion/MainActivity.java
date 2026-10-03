package app.roam.companion;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONException;
import org.json.JSONObject;

/** Hosts the Roam web app in a locked-down WebView and connects it to the phone. */
public final class MainActivity extends Activity {
    private static final int PERMISSIONS = 42;
    private static final int PICK_FILE = 44;
    private static final int SAVE_FILE = 45;
    private static final long FIX_TIMEOUT_MS = 25_000L;
    private static final long POLL_MS = 10_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> permissionQueue = new ArrayDeque<String>();
    private String permissionTask = "";
    private String pendingPlace = "";
    private String pendingShare = "";
    private String pendingSave;
    private String fixPurpose = "current";
    private boolean observingCalendar;
    private ValueCallback<Uri[]> fileCallback;
    private LocationListener oneShot;
    private Object backCallback;
    private WebView web;

    private final Runnable oneShotTimeout = new Runnable() {
        @Override
        public void run() {
            finishFix(null);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshUI();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final ContentObserver calendarObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            refreshCalendar();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        NativeNotifications.channels(this);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web = new WebView(this);
        web.setBackgroundColor(0xFFF8F7F3);
        setContentView(web);
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                // Android 15 draws apps edge to edge; keep content clear of bars and the keyboard.
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                            | WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                }
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(false);
        web.addJavascriptInterface(new WebBridge(this), "RoamAndroid");

        final AssetServer assets = new AssetServer(this);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.handle(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return true;
                Uri u = request.getUrl();
                if ("https".equals(u.getScheme()) && AssetServer.HOST.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                refreshUI();
                deliverIntent();
                refreshCalendar();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The WebView's renderer crashed or was killed for memory: start fresh instead of crashing.
                recreate();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    callback.onReceiveValue(null);
                    fileCallback = null;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= 33) registerBack();
        readIntent(getIntent());
        web.loadUrl(AssetServer.ORIGIN + "/index.html");
        watchCalendar();
    }

    // ---- Back navigation ---------------------------------------------------------------------

    private static final String BACK_JS = "(()=>{const d=document.querySelector('#dialog');"
            + "if(d&&d.open){d.close();return true;}"
            + "if(typeof state!=='undefined'&&state.view!=='discover'){changeView('discover');return true;}"
            + "return false;})()";

    private void handleBack() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript(BACK_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                // Leave Roam in the background rather than destroying it, like the home screen does.
                if (!"true".equals(handled)) moveTaskToBack(true);
            }
        });
    }

    private void registerBack() {
        OnBackInvokedCallback cb = new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                handleBack();
            }
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
        backCallback = cb;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    // ---- JavaScript helpers ------------------------------------------------------------------

    void js(String script) {
        if (web != null) web.evaluateJavascript(script, null);
    }

    void refreshUI() {
        js("window.roamNativeRefresh&&window.roamNativeRefresh()");
    }

    void message(String text) {
        js("window.toast&&toast(" + JSONObject.quote(text) + ")");
    }

    private void deliverIntent() {
        if (pendingPlace != null && !pendingPlace.isEmpty()) {
            js("window.roamNativeOpen&&window.roamNativeOpen(" + JSONObject.quote(pendingPlace) + ")");
            pendingPlace = null;
        }
        if (pendingShare != null && !pendingShare.isEmpty()) {
            js("window.roamNativeShare&&window.roamNativeShare(" + JSONObject.quote(pendingShare) + ")");
            pendingShare = null;
        }
    }

    private void readIntent(Intent intent) {
        pendingPlace = intent.getStringExtra("placeId");
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            pendingShare = text != null && text.length() <= 4000 ? text : null;
        }
    }

    void openExternal(String url) {
        try {
            Uri u = Uri.parse(url);
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) return;
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (RuntimeException e) {
            message("No app could open that link.");
        }
    }

    // ---- Permissions -------------------------------------------------------------------------

    void enqueueTask(String task) {
        permissionQueue.add(task);
        nextTask();
    }

    private void nextTask() {
        if (permissionTask.isEmpty() && !permissionQueue.isEmpty()) task(permissionQueue.remove());
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void ask(String task, String... permissions) {
        permissionTask = task;
        requestPermissions(permissions, PERMISSIONS);
    }

    private void task(String task) {
        if ("calendar".equals(task)) {
            if (!granted(Manifest.permission.READ_CALENDAR)) {
                ask(task, Manifest.permission.READ_CALENDAR);
                return;
            }
            NativeStore.prefs(this).edit().putBoolean("calendar", true).apply();
            refreshCalendar();
        } else if ("home".equals(task) || "current".equals(task)) {
            if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                ask(task, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
                return;
            }
            singleLocation(task);
        } else if ("tracking".equals(task)) {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                ask(task, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
                return;
            }
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                // The companion's status notification and its suggestions both need this.
                permissionQueue.addFirst("tracking");
                ask("notifications", Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
            startTracking();
        } else if ("notifications".equals(task)) {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                ask(task, Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
            if (NativeNotifications.enabled(this)) {
                refreshUI();
            } else {
                startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        }
        nextTask();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != PERMISSIONS) return;
        String task = permissionTask;
        permissionTask = "";
        boolean any = false;
        for (int r : results) any |= r == PackageManager.PERMISSION_GRANTED;
        if ("tracking".equals(task) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            message("Precise location is needed to recognize departures. You can browse with approximate location; "
                    + "enable precise location in Android app settings for tracking.");
        } else if (any) {
            task(task);
            return;
        } else if ("notifications".equals(task) && !permissionQueue.isEmpty() && "tracking".equals(permissionQueue.peek())) {
            // Tracking still works without notifications; Android just won't show its status.
            permissionQueue.remove();
            startTracking();
        } else {
            message("Permission wasn’t granted. You can still use your companion manually.");
        }
        refreshUI();
        nextTask();
    }

    // ---- Location ----------------------------------------------------------------------------

    private void startTracking() {
        if (CompanionService.active) return;
        try {
            NativeStore.prefs(this).edit().putBoolean("trackingWanted", true).apply();
            startForegroundService(new Intent(this, CompanionService.class));
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshUI();
                    message("Departure-aware companion started. You can pause it in the notification.");
                }
            }, 400);
        } catch (RuntimeException e) {
            message("Location companion could not start. Try again while Roam is open.");
        }
    }

    private void singleLocation(String purpose) {
        stopFix();
        fixPurpose = purpose;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean precise = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        try {
            // A fresh, accurate fix from another app answers instantly.
            for (String p : lm.getProviders(true)) {
                Location last = lm.getLastKnownLocation(p);
                if (last != null && last.hasAccuracy() && last.getAccuracy() <= (precise ? 50 : 2000)
                        && SystemClock.elapsedRealtimeNanos() - last.getElapsedRealtimeNanos() < 60_000_000_000L) {
                    oneShot = listener(precise);
                    finishFix(last);
                    return;
                }
            }
            oneShot = listener(precise);
            boolean requested = false;
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER,
                        new LocationRequest.Builder(1000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build(),
                        getMainExecutor(), oneShot);
                requested = true;
            } else {
                for (String p : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    // With approximate location only, older Android refuses GPS requests outright.
                    if (!precise && LocationManager.GPS_PROVIDER.equals(p)) continue;
                    if (lm.isProviderEnabled(p)) {
                        lm.requestLocationUpdates(p, 1000, 0, oneShot, Looper.getMainLooper());
                        requested = true;
                    }
                }
            }
            if (requested) {
                handler.postDelayed(oneShotTimeout, FIX_TIMEOUT_MS);
            } else {
                finishFix(null);
            }
        } catch (RuntimeException e) {
            finishFix(null);
        }
    }

    private LocationListener listener(final boolean precise) {
        return new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                if (!l.hasAccuracy() || SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() > 120_000_000_000L) return;
                if (!precise || l.getAccuracy() <= 200) finishFix(l);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };
    }

    private void stopFix() {
        handler.removeCallbacks(oneShotTimeout);
        if (oneShot != null) {
            ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(oneShot);
            oneShot = null;
        }
    }

    private void finishFix(Location l) {
        if (oneShot == null) return;
        stopFix();
        JSONObject fix = new JSONObject();
        try {
            if (l != null) {
                fix.put("lat", l.getLatitude());
                fix.put("lng", l.getLongitude());
                fix.put("accuracy", (double) l.getAccuracy());
                fix.put("at", System.currentTimeMillis());
                NativeStore.saveLocation(this, l.getLatitude(), l.getLongitude(), l.getAccuracy());
            } else {
                fix.put("error", "Location unavailable. Check phone location settings, search an address, or use coordinates.");
            }
        } catch (JSONException e) {
            return;
        }
        js("home".equals(fixPurpose)
                ? "window.RoamPlanning&&RoamPlanning.homeFix(" + fix + ")"
                : "window.roamNativeFix&&window.roamNativeFix(" + fix + ")");
    }

    // ---- Calendar ----------------------------------------------------------------------------

    void refreshCalendar() {
        if (web == null || !DeviceCalendar.connected(this)) return;
        watchCalendar();
        io.execute(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                final JSONObject result = DeviceCalendar.read(MainActivity.this, now - 31 * Suggestions.DAY, now + 181 * Suggestions.DAY);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        js("window.roamNativeCalendar&&window.roamNativeCalendar(" + result + ")");
                    }
                });
            }
        });
    }

    private void watchCalendar() {
        if (observingCalendar || !DeviceCalendar.permitted(this)) return;
        try {
            getContentResolver().registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendarObserver);
            observingCalendar = true;
        } catch (SecurityException ignored) {
            // permission withdrawn meanwhile
        }
    }

    // ---- Files -------------------------------------------------------------------------------

    void saveFile(String name, String text) {
        pendingSave = text;
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, name), SAVE_FILE);
        } catch (ActivityNotFoundException e) {
            pendingSave = null;
            message("This phone has no app for saving files.");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(result == RESULT_OK && data != null && data.getData() != null
                    ? new Uri[] {data.getData()} : null);
            fileCallback = null;
        } else if (code == SAVE_FILE) {
            final String text = pendingSave;
            pendingSave = null;
            if (result != RESULT_OK || data == null || data.getData() == null || text == null) return;
            final Uri target = data.getData();
            io.execute(new Runnable() {
                @Override
                public void run() {
                    boolean ok = false;
                    try {
                        OutputStream out = getContentResolver().openOutputStream(target, "wt");
                        if (out != null) {
                            try {
                                out.write(text.getBytes(StandardCharsets.UTF_8));
                                ok = true;
                            } finally {
                                out.close();
                            }
                        }
                    } catch (Exception ignored) {
                        // reported below
                    }
                    final boolean saved = ok;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            message(saved ? "Backup saved. Keep it somewhere safe." : "The backup couldn’t be saved there. Try another folder.");
                        }
                    });
                }
            });
        }
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        deliverIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
        refreshCalendar();
        if (NativeStore.trackingWanted(this) && !CompanionService.active && granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startTracking();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        stopFix();
        if (observingCalendar) getContentResolver().unregisterContentObserver(calendarObserver);
        io.shutdownNow();
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
        }
        if (web != null) {
            web.removeJavascriptInterface("RoamAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
