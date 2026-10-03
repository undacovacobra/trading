package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SuggestionsTest {
    static final long NOW = 1_780_000_000_000L;
    static final ZonedDateTime EVENING = ZonedDateTime.of(2026, 7, 10, 18, 0, 0, 0, ZoneId.of("UTC"));

    @Test
    public void noAndRecentNotNowRuleAPlaceOut() throws Exception {
        JSONArray replies = new JSONArray()
                .put(new JSONObject().put("placeId", "a").put("feeling", "no").put("at", NOW - 5 * 86_400_000L))
                .put(new JSONObject().put("placeId", "b").put("feeling", "later").put("at", NOW - 3_600_000L))
                .put(new JSONObject().put("placeId", "c").put("feeling", "later").put("at", NOW - 2 * 86_400_000L));
        assertTrue(Suggestions.declined(replies, "a", NOW));
        assertTrue(Suggestions.declined(replies, "b", NOW));
        assertFalse(Suggestions.declined(replies, "c", NOW));
    }

    @Test
    public void busyOnlyDuringTheEvent() throws Exception {
        JSONArray busy = new JSONArray().put(new JSONObject().put("start", NOW - 60_000).put("end", NOW + 60_000));
        assertTrue(Suggestions.busyIn(busy, NOW));
        assertFalse(Suggestions.busyIn(busy, NOW + 120_000));
    }

    @Test
    public void countsTodaysDeliveriesInTheGivenZone() {
        JSONArray d = new JSONArray().put(EVENING.toInstant().toEpochMilli()).put(EVENING.minusDays(1).toInstant().toEpochMilli());
        assertEquals(1, Suggestions.sentOn(d, EVENING.toLocalDate(), ZoneId.of("UTC")));
    }
}
