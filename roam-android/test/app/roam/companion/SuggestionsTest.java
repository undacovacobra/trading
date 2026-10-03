package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SuggestionsTest {
    static final double LAT = DepartureDetectorTest.LAT;
    static final double LNG = DepartureDetectorTest.LNG;
    static final long NOW = 1_780_000_000_000L;
    static final ZonedDateTime EVENING = ZonedDateTime.of(2026, 7, 10, 18, 0, 0, 0, ZoneId.of("UTC"));
    static final ZonedDateTime MORNING = EVENING.withHour(9);

    static JSONObject place(String id, double metersNorth, double rank) throws Exception {
        return new JSONObject().put("id", id).put("name", id).put("lat", DepartureDetectorTest.north(metersNorth))
                .put("lng", LNG).put("rank", rank).put("reason", "Fits you");
    }

    private static JSONObject pick(JSONArray places, JSONArray replies, ZonedDateTime at) throws Exception {
        JSONObject snapshot = new JSONObject().put("places", places);
        return Suggestions.bestAfterDeparture(snapshot, Suggestions.departureCandidates(snapshot, new JSONArray()),
                replies, LAT, LNG, DepartureDetectorTest.north(-2000), LNG, NOW, at);
    }

    @Test
    public void picksTheBestNearbyPlace() throws Exception {
        JSONArray places = new JSONArray()
                .put(place("too-close", 100, 100))
                .put(place("too-far", 5000, 100))
                .put(place("good", 800, 40))
                .put(place("better", 900, 60));
        assertEquals("better", pick(places, new JSONArray(), EVENING).optString("id"));
    }

    @Test
    public void respectsNoAndNotNow() throws Exception {
        JSONArray places = new JSONArray().put(place("a", 800, 50)).put(place("b", 900, 40));
        JSONArray replies = new JSONArray().put(new JSONObject().put("placeId", "a").put("feeling", "no").put("at", NOW - 5 * 86_400_000L));
        assertEquals("b", pick(places, replies, EVENING).optString("id"));
        replies.put(new JSONObject().put("placeId", "b").put("feeling", "later").put("at", NOW - 3_600_000L));
        assertNull(pick(places, replies, EVENING));
    }

    @Test
    public void lateNightPlacesWaitForEvening() throws Exception {
        JSONArray places = new JSONArray().put(place("club", 800, 50).put("lateNight", true));
        assertNull(pick(places, new JSONArray(), MORNING));
        assertNotNull(pick(places, new JSONArray(), EVENING));
    }

    @Test
    public void seasonsUseTheGivenMonth() throws Exception {
        JSONArray places = new JSONArray().put(place("ski", 800, 50).put("months", new JSONArray().put(12).put(1)));
        assertNull(pick(places, new JSONArray(), EVENING));
        assertNotNull(pick(places, new JSONArray(), EVENING.withMonth(1)));
    }

    @Test
    public void eventsMustStartWithinThreeHours() throws Exception {
        JSONArray places = new JSONArray()
                .put(place("tonight", 800, 50).put("start", NOW + 2 * 3_600_000L))
                .put(place("tomorrow", 800, 90).put("start", NOW + 26 * 3_600_000L));
        assertEquals("tonight", pick(places, new JSONArray(), EVENING).optString("id"));
    }

    @Test
    public void countsTodaysDeliveriesInTheGivenZone() {
        JSONArray d = new JSONArray().put(EVENING.toInstant().toEpochMilli()).put(EVENING.minusDays(1).toInstant().toEpochMilli());
        assertEquals(1, Suggestions.sentOn(d, EVENING.toLocalDate(), ZoneId.of("UTC")));
    }

    @Test
    public void liveCandidatesNeedAMatchingInterest() throws Exception {
        JSONObject snapshot = new JSONObject().put("interests", new JSONArray().put("Coffee"));
        JSONObject cafe = place("osm-node-1", 800, 0).put("tags", new JSONArray().put("Coffee")).put("checkedAt", NOW);
        JSONObject bar = place("osm-node-2", 800, 0).put("tags", new JSONArray().put("Nightlife")).put("checkedAt", NOW);
        JSONArray found = LiveCandidates.nearby(new JSONArray().put(cafe).put(bar), snapshot, LAT, LNG, NOW);
        assertEquals(1, found.length());
        assertEquals("osm-node-1", found.getJSONObject(0).getString("id"));
        assertTrue(found.getJSONObject(0).getBoolean("live"));
    }
}
