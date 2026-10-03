package app.roam.companion;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only access to the calendars on the phone (Google, Samsung, Outlook… whatever is synced
 * and visible), so Plans can show when you're busy. Roam never adds, edits or deletes events.
 */
final class DeviceCalendar {
    static final int MAX_RECORDS = 3000;
    static final long MAX_RANGE_MS = 400L * 24 * 3_600_000L;

    private DeviceCalendar() {}

    static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Permission granted and you haven't switched it off in Roam. */
    static boolean connected(Context c) {
        return permitted(c) && NativeStore.prefs(c).getBoolean("calendar", true);
    }

    static JSONObject read(Context c, long start, long end) {
        JSONObject out = new JSONObject();
        try {
            if (!connected(c)) return out.put("status", permitted(c) ? "off" : "needs-permission");
            if (end <= start || end - start > MAX_RANGE_MS) return out.put("status", "bad-range");

            Map<Long, String> calendars = new HashMap<Long, String>();
            Cursor cals = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                    new String[] {CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME},
                    CalendarContract.Calendars.VISIBLE + "=1", null, null);
            if (cals != null) {
                try {
                    while (cals.moveToNext()) calendars.put(cals.getLong(0), cals.getString(1));
                } finally {
                    cals.close();
                }
            }

            Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(uri, start);
            ContentUris.appendId(uri, end);
            JSONArray records = new JSONArray();
            Cursor cur = c.getContentResolver().query(uri.build(), new String[] {
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.AVAILABILITY, CalendarContract.Instances.STATUS,
            }, null, null, CalendarContract.Instances.BEGIN + " ASC");
            if (cur != null) {
                try {
                    while (cur.moveToNext()) {
                        if (!calendars.containsKey(cur.getLong(1))) continue;
                        if (cur.getInt(7) == CalendarContract.Events.STATUS_CANCELED) continue;
                        boolean allDay = cur.getInt(5) == 1;
                        JSONObject r = new JSONObject();
                        r.put("id", "cal-" + cur.getLong(0) + "-" + cur.getLong(3));
                        r.put("title", cur.isNull(2) ? "Busy" : Json.clip(cur.getString(2), 200));
                        r.put("start", cur.getLong(3));
                        r.put("end", cur.getLong(4));
                        r.put("allDay", allDay);
                        r.put("free", cur.getInt(6) == CalendarContract.Events.AVAILABILITY_FREE);
                        if (allDay) {
                            // All-day events are stored at UTC midnight; hand the dates over as dates.
                            r.put("dateStart", Instant.ofEpochMilli(cur.getLong(3)).atZone(ZoneOffset.UTC).toLocalDate().toString());
                            r.put("dateEnd", Instant.ofEpochMilli(cur.getLong(4)).atZone(ZoneOffset.UTC).toLocalDate().toString());
                        }
                        records.put(r);
                        if (records.length() >= MAX_RECORDS) {
                            out.put("truncated", true);
                            break;
                        }
                    }
                } finally {
                    cur.close();
                }
            }
            return out.put("status", "connected").put("calendars", calendars.size()).put("records", records)
                    .put("start", start).put("end", end).put("checkedAt", System.currentTimeMillis());
        } catch (Exception e) {
            return HomeSearch.error("Couldn't read your calendar.");
        }
    }
}
