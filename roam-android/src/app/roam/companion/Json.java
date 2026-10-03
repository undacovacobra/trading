package app.roam.companion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small helpers for the JSON the web app and the native side exchange. */
final class Json {
    private Json() {}

    static JSONObject object(String text) {
        try {
            return new JSONObject(text == null ? "{}" : text);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static JSONArray array(String text) {
        try {
            return new JSONArray(text == null ? "[]" : text);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static boolean contains(JSONArray list, String value) {
        if (list == null || value == null) return false;
        for (int i = 0; i < list.length(); i++) {
            if (value.equals(list.optString(i))) return true;
        }
        return false;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
