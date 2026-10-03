package app.roam.companion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only access to the Google calendars synced on the phone, used to avoid busy times. */
final class DeviceCalendar {
    static final int MAX_RECORDS = 5000;

    private DeviceCalendar() {}

    static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean connected(Context c) {
        return permitted(c) && NativeStore.prefs(c).getBoolean("calendar", false);
    }

    static Set<Long> googleCalendars(Context c) {
        Set<Long> ids = new HashSet<Long>();
        Cursor cur = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                new String[] {CalendarContract.Calendars._ID},
                CalendarContract.Calendars.ACCOUNT_TYPE + "=? AND " + CalendarContract.Calendars.VISIBLE + "=1 AND "
                        + CalendarContract.Calendars.SYNC_EVENTS + "=1",
                new String[] {"com.google"}, null);
        if (cur == null) return ids;
        try {
            while (cur.moveToNext()) ids.add(cur.getLong(0));
        } finally {
            cur.close();
        }
        return ids;
    }

    static JSONObject read(Context c, long start, long end) {
        JSONObject out = new JSONObject();
        try {
            out.put("start", start);
            out.put("end", end);
            if (!connected(c)) return out.put("status", "disconnected");
            Set<Long> calendars = googleCalendars(c);
            out.put("calendarCount", calendars.size());
            if (calendars.isEmpty()) return out.put("status", "no-google-calendars");

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
                        if (!calendars.contains(cur.getLong(1))) continue;
                        if (cur.getInt(6) == CalendarContract.Events.AVAILABILITY_FREE) continue;
                        if (cur.getInt(7) == CalendarContract.Events.STATUS_CANCELED) continue;
                        boolean allDay = cur.getInt(5) == 1;
                        JSONObject r = new JSONObject();
                        r.put("id", "android-" + cur.getLong(0) + "-" + cur.getLong(3));
                        r.put("title", cur.isNull(2) ? "Busy" : cur.getString(2));
                        r.put("start", cur.getLong(3));
                        r.put("end", cur.getLong(4));
                        r.put("allDay", allDay);
                        r.put("source", "Google Calendar on your phone");
                        if (allDay) {
                            // All-day events are stored at UTC midnight.
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
            out.put("status", "connected");
            out.put("records", records);
            out.put("checkedAt", System.currentTimeMillis());
            return out;
        } catch (Exception e) {
            try {
                return out.put("status", "unavailable");
            } catch (JSONException ignored) {
                return out;
            }
        }
    }

    static boolean busyBetween(Context c, long start, long end) {
        JSONArray records = read(c, start - Suggestions.DAY, end + Suggestions.DAY).optJSONArray("records");
        if (records == null) return false;
        ZoneId zone = ZoneId.systemDefault();
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            long s = r.optLong("start");
            long e = r.optLong("end");
            if (r.optBoolean("allDay")) {
                try {
                    s = LocalDate.parse(r.optString("dateStart")).atStartOfDay(zone).toInstant().toEpochMilli();
                    e = LocalDate.parse(r.optString("dateEnd")).atStartOfDay(zone).toInstant().toEpochMilli();
                } catch (Exception ignored) {
                    continue;
                }
            }
            if (s < end && e > start) return true;
        }
        return false;
    }

    static boolean busyNow(Context c, long now) {
        return busyBetween(c, now, now + 1);
    }
}
