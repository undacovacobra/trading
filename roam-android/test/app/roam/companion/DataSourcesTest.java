package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The shapes below follow each provider's documented responses. */
public class DataSourcesTest {
    static final String GOOGLE_PLACE = "{"
            + "\"id\":\"ChIJabc123\",\"types\":[\"tea_house\",\"cafe\",\"food\",\"point_of_interest\"],"
            + "\"displayName\":{\"text\":\"Boulder Dushanbe Teahouse\",\"languageCode\":\"en\"},"
            + "\"primaryTypeDisplayName\":{\"text\":\"Tea house\",\"languageCode\":\"en-US\"},"
            + "\"location\":{\"latitude\":40.0156,\"longitude\":-105.2775},"
            + "\"rating\":4.6,\"userRatingCount\":3120,\"priceLevel\":\"PRICE_LEVEL_MODERATE\",\"businessStatus\":\"OPERATIONAL\","
            + "\"shortFormattedAddress\":\"1770 13th St, Boulder\",\"googleMapsUri\":\"https://maps.google.com/?cid=1\","
            + "\"websiteUri\":\"https://boulderteahouse.com/\","
            + "\"regularOpeningHours\":{\"openNow\":true,\"periods\":[{\"open\":{\"day\":1,\"hour\":8,\"minute\":0},"
            + "\"close\":{\"day\":1,\"hour\":21,\"minute\":0}}],\"weekdayDescriptions\":[\"Monday: 8:00 AM – 9:00 PM\"]},"
            + "\"photos\":[{\"name\":\"places/ChIJabc123/photos/AbC-1_x\",\"widthPx\":4032,\"heightPx\":3024,"
            + "\"authorAttributions\":[{\"displayName\":\"Sam Rivera\",\"uri\":\"https://maps.google.com/maps/contrib/1\"}]}]}";

    @Test
    public void normalizesAGooglePlace() throws Exception {
        JSONObject p = GooglePlaces.normalize(new JSONObject(GOOGLE_PLACE));
        assertEquals("g:ChIJabc123", p.getString("id"));
        assertEquals("Boulder Dushanbe Teahouse", p.getString("name"));
        assertEquals("Tea house", p.getString("kind"));
        assertEquals(2, p.getInt("price"));
        assertEquals(3120, p.getInt("ratings"));
        JSONArray tags = p.getJSONArray("tags");
        assertEquals("coffee", tags.getString(0));
        assertEquals("quiet", tags.getString(1));
        assertEquals("g:places/ChIJabc123/photos/AbC-1_x", p.getJSONArray("photos").getJSONObject(0).getString("ref"));
        assertEquals("Sam Rivera", p.getJSONArray("photos").getJSONObject(0).getString("by"));
        assertEquals(1, p.getJSONObject("hours").getJSONArray("periods").length());
    }

    @Test
    public void skipsClosedAndUntaggedPlaces() throws Exception {
        JSONObject closed = new JSONObject(GOOGLE_PLACE).put("businessStatus", "CLOSED_PERMANENTLY");
        assertNull(GooglePlaces.normalize(closed));
        JSONObject gasStation = new JSONObject(GOOGLE_PLACE).put("types", new JSONArray().put("gas_station"));
        assertNull(GooglePlaces.normalize(gasStation));
    }

    @Test
    public void restaurantSubtypesCountAsFood() {
        assertTrue(PlaceTags.fromGoogle(new JSONArray().put("thai_restaurant")).contains("food"));
    }

    @Test
    public void nearbyBodyIsWellFormed() throws Exception {
        JSONObject body = new JSONObject(GooglePlaces.nearbyBody(new String[] {"cafe"}, 6000, 40.0, -105.0));
        assertEquals(20, body.getInt("maxResultCount"));
        assertEquals(6000.0, body.getJSONObject("locationRestriction").getJSONObject("circle").getDouble("radius"), 0);
        assertEquals("cafe", body.getJSONArray("includedTypes").getString(0));
    }

    @Test
    public void normalizesOsmElements() throws Exception {
        JSONObject el = new JSONObject("{\"type\":\"way\",\"id\":123,\"center\":{\"lat\":40.01,\"lon\":-105.27},"
                + "\"tags\":{\"name\":\"Trident Booksellers\",\"shop\":\"books\",\"amenity\":\"cafe\","
                + "\"wikimedia_commons\":\"File:Trident.jpg\",\"opening_hours\":\"Mo-Su 07:00-20:00\"}}");
        JSONObject p = OsmPlaces.normalize(el);
        assertEquals("osm-way-123", p.getString("id"));
        assertEquals("Café", p.getString("kind"));
        String tags = p.getJSONArray("tags").toString();
        assertTrue(tags.contains("coffee") && tags.contains("books"));
        assertEquals("c:File:Trident.jpg", p.getJSONArray("photos").getJSONObject(0).getString("ref"));
        assertTrue(OsmPlaces.query(40, -105, 8000).contains("nwr(around:8000,40.0,-105.0)[\"amenity\""));
    }

    @Test
    public void normalizesTicketmasterEventsAndFoldsRepeats() throws Exception {
        String event = "{\"name\":\"Big Thief\",\"id\":\"%s\",\"url\":\"https://www.ticketmaster.com/e/1\","
                + "\"images\":[{\"ratio\":\"3_2\",\"url\":\"https://s1.ticketm.net/a.jpg\",\"width\":640},"
                + "{\"ratio\":\"16_9\",\"url\":\"https://s1.ticketm.net/b.jpg\",\"width\":1024},"
                + "{\"ratio\":\"16_9\",\"url\":\"https://s1.ticketm.net/c.jpg\",\"width\":2048}],"
                + "\"dates\":{\"start\":{\"localDate\":\"%s\",\"localTime\":\"19:30:00\",\"dateTime\":\"%sT01:30:00Z\"},"
                + "\"timezone\":\"America/Denver\",\"status\":{\"code\":\"onsale\"}},"
                + "\"classifications\":[{\"segment\":{\"name\":\"Music\"},\"genre\":{\"name\":\"Rock\"}}],"
                + "\"priceRanges\":[{\"type\":\"standard\",\"currency\":\"USD\",\"min\":59.5,\"max\":120}],"
                + "\"_embedded\":{\"venues\":[{\"name\":\"Red Rocks Amphitheatre\",\"city\":{\"name\":\"Morrison\"},"
                + "\"location\":{\"longitude\":\"-105.2052\",\"latitude\":\"39.6654\"}}]}}";
        JSONObject response = new JSONObject().put("_embedded", new JSONObject().put("events", new JSONArray()
                .put(new JSONObject(String.format(event, "A1", "2026-10-06", "2026-10-07")))
                .put(new JSONObject(String.format(event, "A2", "2026-10-07", "2026-10-08")))));
        JSONArray events = Events.normalizeAll(response);
        assertEquals(1, events.length());
        JSONObject e = events.getJSONObject(0);
        assertEquals("tm:A1", e.getString("id"));
        assertEquals(1, e.getInt("moreDates"));
        assertEquals("19:30", e.getString("localTime"));
        assertEquals("https://s1.ticketm.net/b.jpg", e.getString("image"));
        assertEquals("music", e.getJSONArray("tags").getString(0));
        assertEquals("Rock", e.getString("genre"));
        assertEquals(39.6654, e.getDouble("lat"), 1e-6);
    }

    @Test
    public void normalizesOpenMeteo() throws Exception {
        JSONObject r = new JSONObject("{\"utc_offset_seconds\":-21600,\"current\":{\"temperature_2m\":18.0,"
                + "\"weather_code\":61,\"is_day\":1},\"daily\":{\"sunset\":[\"2026-10-03T18:41\"],\"sunrise\":[\"2026-10-03T07:03\"]}}");
        JSONObject w = Weather.normalize(r);
        assertEquals(64, w.getLong("tempF"));
        assertEquals("rain", w.getString("sky"));
        assertEquals(ZonedDateTime.of(2026, 10, 3, 18, 41, 0, 0, ZoneId.of("America/Denver")).toInstant().toEpochMilli(),
                w.getLong("sunset"));
    }

    @Test
    public void weeklyPicksAreVaried() throws Exception {
        long now = ZonedDateTime.of(2026, 10, 2, 17, 0, 0, 0, ZoneId.of("America/Denver")).toInstant().toEpochMilli();
        JSONArray c = new JSONArray()
                .put(new JSONObject().put("id", "a").put("score", 9).put("tags", new JSONArray().put("coffee")).put("line", "a slow coffee at Boxcar"))
                .put(new JSONObject().put("id", "b").put("score", 8).put("tags", new JSONArray().put("coffee")).put("line", "tea at the Teahouse"))
                .put(new JSONObject().put("id", "c").put("score", 7).put("tags", new JSONArray().put("trails")).put("line", "the loop at Chautauqua"))
                .put(new JSONObject().put("id", "past").put("type", "event").put("score", 99).put("start", now - 1000))
                .put(new JSONObject().put("id", "show").put("type", "event").put("score", 6).put("start", now + 4 * 86_400_000L)
                        .put("tags", new JSONArray().put("music")).put("line", "Big Thief at Red Rocks"));
        List<JSONObject> picks = WeeklyPicks.choose(c, now, 3);
        assertEquals(3, picks.size());
        assertEquals("a", picks.get(0).getString("id"));
        assertEquals("c", picks.get(1).getString("id"));
        assertEquals("show", picks.get(2).getString("id"));
        String text = WeeklyPicks.message(picks, ZonedDateTime.of(2026, 10, 2, 17, 0, 0, 0, ZoneId.of("America/Denver")));
        assertTrue(text, text.startsWith("A slow coffee at Boxcar, the loop at Chautauqua, and Big Thief at Red Rocks on "));
        assertFalse(text.contains("past"));
    }

    @Test
    public void weeklyAlarmLandsOnTheChosenDay() {
        ZonedDateTime thu = ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZoneId.of("America/Denver"));
        ZonedDateTime next = WeeklyPicks.next(thu, 5, "17:00");
        assertEquals(java.time.DayOfWeek.FRIDAY, next.getDayOfWeek());
        assertEquals(17, next.getHour());
        ZonedDateTime fridayEvening = ZonedDateTime.of(2026, 10, 2, 18, 0, 0, 0, ZoneId.of("America/Denver"));
        assertEquals(9, WeeklyPicks.next(fridayEvening, 5, "17:00").getDayOfMonth());
    }
}
