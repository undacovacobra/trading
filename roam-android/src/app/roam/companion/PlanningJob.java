package app.roam.companion;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Runs every few hours (Android picks the exact time) to send "near home" and "before your trip"
 * ideas even when Roam is closed. The choice itself lives in {@link Planner}.
 */
public final class PlanningJob extends JobService {
    static final int ID = 203;
    private Thread work;

    @Override
    public boolean onStartJob(final JobParameters params) {
        work = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver();
                } catch (Exception ignored) {
                    // try again next period
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "roam-planning");
        work.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (work != null) work.interrupt();
        return true;
    }

    private void deliver() throws JSONException {
        final Context c = this;
        JSONObject snapshot = NativeStore.snapshot(c);
        JSONObject planning = snapshot.optJSONObject("planning");
        if (planning == null || !snapshot.optBoolean("notifications")) return;

        long now = System.currentTimeMillis();
        JSONObject quiet = snapshot.optJSONObject("quiet");
        boolean isQuiet = quiet != null && NudgePolicy.quiet(quiet.optString("start"), quiet.optString("end"), LocalTime.now());
        boolean busy = DeviceCalendar.busyNow(c, now) || Suggestions.busyIn(snapshot.optJSONArray("busy"), now);
        if (!NudgePolicy.allowed(snapshot.optInt("max", 3), NativeStore.sentToday(c), isQuiet, busy,
                NativeStore.prefs(c).getLong("lastNudge", 0), now)) {
            return;
        }

        final JSONObject snap = snapshot;
        JSONObject ledger = Planner.mergeLedger(NativeStore.ledger(c), planning.optJSONObject("ledger"));
        boolean away = Planner.homeAway(planning, NativeStore.location(c), now);
        Planner.Pick pick = Planner.choose(planning, ledger, NativeStore.replies(c), away, now, new Planner.Sources() {
            @Override
            public JSONArray liveNear(double lat, double lng, long at) {
                DiscoverySource.refreshInBackground(c, lat, lng);
                return LiveCandidates.nearby(NativeStore.livePlaces(c), snap, lat, lng, at);
            }

            @Override
            public boolean busyBetween(long start, long end) {
                return DeviceCalendar.busyBetween(c, start, end);
            }
        });
        if (pick == null || Thread.currentThread().isInterrupted()) return;

        String id = pick.place.optString("id");
        String body = pick.place.optString("body");
        if (!NativeNotifications.suggestion(c, id, pick.place.optString("name"), body)) return;
        Planner.record(ledger, pick.task, id, now);
        NativeStore.recordDelivery(c, now, ledger);
        JSONObject note = new JSONObject();
        note.put("key", pick.task.optString("key"));
        note.put("kind", pick.task.optString("kind"));
        note.put("name", pick.task.optString("name"));
        note.put("body", body);
        NativeStore.reply(c, id, "planning", note.toString());
    }

    /** Called whenever the web app saves: schedules or cancels the periodic job, tidies old homes. */
    static void schedule(Context c) {
        JSONObject snapshot = NativeStore.snapshot(c);
        JSONObject planning = snapshot.optJSONObject("planning");
        JSONArray tasks = planning == null ? null : planning.optJSONArray("tasks");
        boolean wanted = false;
        Set<String> keys = new HashSet<String>();
        if (tasks != null) {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject t = tasks.optJSONObject(i);
                if (t == null) continue;
                wanted |= t.optLong("every") > 0;
                keys.add(t.optString("key"));
            }
        }
        JobScheduler scheduler = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (wanted && snapshot.optBoolean("notifications") && snapshot.optInt("max", 3) != 0) {
            if (scheduler.getPendingJob(ID) == null) {
                scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(c, PlanningJob.class))
                        .setPeriodic(6 * 3_600_000L, 3 * 3_600_000L)
                        .setPersisted(true)
                        .build());
            }
        } else {
            scheduler.cancel(ID);
        }

        // Forget ledger entries for a previous Home.
        JSONObject ledger = NativeStore.ledger(c);
        List<String> stale = new ArrayList<String>();
        Iterator<String> it = ledger.keys();
        while (it.hasNext()) {
            String key = it.next();
            if ((key.equals("home") || key.startsWith("home:")) && !keys.contains(key)) stale.add(key);
        }
        if (stale.isEmpty()) return;
        for (String key : stale) ledger.remove(key);
        NativeStore.prefs(c).edit().putString("planningLedger", ledger.toString()).apply();
    }
}
