package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CuratorTest {
    static final ZoneId DENVER = ZoneId.of("America/Denver");
    // A Thursday at 4:30 pm.
    static final ZonedDateTime THU = ZonedDateTime.of(2026, 10, 8, 16, 30, 0, 0, DENVER);
    static final JSONObject HERE = obj("{\"lat\":40.0176,\"lng\":-105.2797}");

    static JSONObject obj(String json) {
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static JSONObject place(String id, String name, String kind, String tag, double rating, int n, double north) throws Exception {
        JSONArray periods = new JSONArray();
        for (int d = 0; d < 7; d++) {
            periods.put(new JSONObject().put("open", new JSONObject().put("day", d).put("hour", 8).put("minute", 0))
                    .put("close", new JSONObject().put("day", d).put("hour", 22).put("minute", 0)));
        }
        return new JSONObject().put("id", id).put("source", "google").put("name", name).put("kind", kind)
                .put("tags", new JSONArray().put(tag)).put("rating", rating).put("ratings", n)
                .put("lat", 40.0176 + north / 111_195.0).put("lng", -105.2797)
                .put("hours", new JSONObject().put("periods", periods))
                .put("photos", new JSONArray());
    }

    @Test
    public void chainsAndFastFoodNeverQualify() throws Exception {
        JSONArray all = new JSONArray()
                .put(place("a", "Starbucks Pearl St", "Coffee shop", "coffee", 4.6, 900, 100))
                .put(place("b", "Ozo Coffee", "Coffee shop", "coffee", 4.6, 300, 200))
                .put(place("c", "Ozo Coffee - Downtown", "Coffee shop", "coffee", 4.6, 300, 400))
                .put(place("d", "Moxie Bread", "Bakery", "sweet", 4.7, 400, 300).put("types", new JSONArray().put("fast_food_restaurant")))
                .put(place("e", "Boxcar Coffee", "Coffee shop", "coffee", 4.7, 1200, 500));
        java.util.Map<String, Integer> counts = Curator.nameCounts(all);
        assertFalse("big chain", Curator.worthIt(all.getJSONObject(0), counts));
        assertFalse("same name twice nearby", Curator.worthIt(all.getJSONObject(1), counts));
        assertFalse("fast food", Curator.worthIt(all.getJSONObject(3), counts));
        assertTrue(Curator.worthIt(all.getJSONObject(4), counts));
    }

    @Test
    public void qualityBarAllowsHiddenGemsButNotFewReviews() throws Exception {
        java.util.Map<String, Integer> none = new java.util.HashMap<String, Integer>();
        assertFalse(Curator.worthIt(place("a", "A", "Cafe", "coffee", 4.4, 2000, 0), none));
        assertFalse(Curator.worthIt(place("b", "B", "Cafe", "coffee", 5.0, 9, 0), none));
        assertTrue("hidden gem", Curator.worthIt(place("c", "C", "Cafe", "coffee", 4.8, 40, 0), none));
        assertTrue(Curator.hiddenGem(place("c", "C", "Cafe", "coffee", 4.8, 40, 0)));
        assertFalse("free map data without notability", Curator.worthIt(new JSONObject().put("source", "osm").put("name", "Park"), none));
        assertTrue(Curator.worthIt(new JSONObject().put("source", "osm").put("name", "Red Rocks").put("notable", true), none));
    }

    @Test
    public void findsWhatReviewersKeepMentioning() throws Exception {
        JSONArray reviews = new JSONArray()
                .put(new JSONObject().put("rating", 5).put("text", "The green chile is unreal. Sat on the patio for hours.").put("by", "Ana"))
                .put(new JSONObject().put("rating", 5).put("text", "Come for the green chile, stay for the patio and the sunset.").put("by", "Ben"))
                .put(new JSONObject().put("rating", 4).put("text", "Great service, friendly staff, really good food.").put("by", "Cy"))
                .put(new JSONObject().put("rating", 2).put("text", "Green chile was cold.").put("by", "Dee"));
        List<String> hl = Curator.highlights(reviews, 3);
        assertEquals("green chile", hl.get(0));
        assertTrue(hl.toString(), hl.contains("patio"));
        assertFalse("generic praise is ignored", hl.contains("service") || hl.contains("great"));
        JSONObject q = Curator.quote(reviews, hl);
        assertNotNull(q);
        assertTrue(q.getString("text"), q.getString("text").contains("green chile"));
    }

    @Test
    public void shortlistRespectsTasteHoursAndVariety() throws Exception {
        JSONObject weights = new JSONObject().put("coffee", 3).put("views", 2);
        JSONArray all = new JSONArray()
                .put(place("cafe", "Boxcar Coffee", "Coffee shop", "coffee", 4.7, 1200, 500))
                .put(place("view", "Flagstaff Summit", "Scenic spot", "views", 4.8, 900, 3000))
                .put(place("bar", "Bitter Bar", "Cocktail bar", "drinks", 4.6, 500, 300));
        all.getJSONObject(2).getJSONObject("hours").put("periods", new JSONArray().put(new JSONObject()
                .put("open", new JSONObject().put("day", 4).put("hour", 23).put("minute", 0))
                .put("close", new JSONObject().put("day", 5).put("hour", 2).put("minute", 0))));
        Curator.Moment m = new Curator.Moment(THU, null);
        List<JSONObject> list = Curator.shortlist(all, new JSONArray(), weights, 5, HERE, new HashSet<String>(), new ArrayList<String>(), m, 5);
        assertEquals(2, list.size());
        assertFalse("closed for the evening", list.toString().contains("Bitter Bar"));
        List<String> recent = new ArrayList<String>();
        recent.add(Curator.kindOf(list.get(0)));
        List<JSONObject> again = Curator.shortlist(all, new JSONArray(), weights, 5, HERE, new HashSet<String>(), recent, m, 5);
        assertTrue("yesterday's kind drops down", !again.get(0).getString("id").equals(list.get(0).getString("id")));
    }

    @Test
    public void eventsTonightMakeTheShortlistAndTheHeadline() throws Exception {
        long start = THU.withHour(19).withMinute(30).toInstant().toEpochMilli();
        JSONObject show = new JSONObject().put("id", "tm:1").put("type", "event").put("name", "Big Thief")
                .put("venue", "Red Rocks").put("start", start).put("tags", new JSONArray().put("music"))
                .put("lat", 39.6654).put("lng", -105.2052);
        Curator.Moment m = new Curator.Moment(THU, null);
        List<JSONObject> list = Curator.shortlist(new JSONArray(), new JSONArray().put(show), new JSONObject().put("music", 2),
                5, HERE, new HashSet<String>(), new ArrayList<String>(), m, 3);
        assertEquals(1, list.size());
        JSONObject words = Curator.compose(list.get(0), null, m, HERE, new JSONObject().put("music", 2), null);
        assertEquals("Big Thief plays Red Rocks tonight at 7:30 pm", words.getString("headline"));
    }

    @Test
    public void headlinesComeFromSpecifics() throws Exception {
        Curator.Moment m = new Curator.Moment(THU, null);
        JSONObject p = place("g:x", "Lucile's", "Restaurant", "food", 4.6, 2100, 400);
        JSONObject details = new JSONObject().put("summary", "Creole breakfast spot in a Victorian house.").put("reviews", new JSONArray()
                .put(new JSONObject().put("rating", 5).put("text", "The beignets were perfect and the chicory coffee too."))
                .put(new JSONObject().put("rating", 5).put("text", "Get the beignets. Trust me, the beignets are worth the wait.")));
        JSONObject w = Curator.compose(p, details, m, HERE, new JSONObject().put("food", 2),
                new JSONObject().put("food", new JSONObject().put("save", 3)));
        assertTrue(w.getString("headline"), w.getString("headline").endsWith("beignets"));
        assertEquals("Creole breakfast spot in a Victorian house.", w.getString("about"));
        assertTrue(w.getJSONArray("why").getString(0).startsWith("You've saved 3 places to eat"));
        assertTrue(w.getString("practical"), w.getString("practical").startsWith("Open until 10 pm"));
    }

    // ---- Heading out ---------------------------------------------------------------------------

    static JSONObject at(double north) throws Exception {
        return new JSONObject().put("lat", 40.0176 + north / 111_195.0).put("lng", -105.2797);
    }

    static List<JSONObject> leaving(JSONArray places, JSONObject weights, double leftNorth, boolean driving, String favor) throws Exception {
        return Curator.afterLeaving(places, weights, HERE, at(leftNorth), new HashSet<String>(), new Curator.Moment(THU, null),
                driving, favor, 3);
    }

    @Test
    public void afterLeavingOnlySuggestsCloseOpenThingsYouLike() throws Exception {
        JSONObject weights = new JSONObject().put("books", 3).put("coffee", 2);
        JSONArray places = new JSONArray()
                .put(place("left", "Ozo Coffee", "Coffee shop", "coffee", 4.6, 300, -300))
                .put(place("cafe", "Boxcar Coffee", "Coffee shop", "coffee", 4.7, 1200, 400))
                .put(place("books", "Boulder Book Store", "Book store", "books", 4.8, 1900, 600))
                .put(place("far", "Tattered Cover", "Book store", "books", 4.8, 5000, 4000))
                .put(place("meh", "Rayback", "Bar", "drinks", 4.8, 1400, 300));
        List<JSONObject> list = leaving(places, weights, -300, false, null);
        assertEquals(1, list.size());
        assertEquals("not another coffee shop right after one, not far, not a kind you never chose", "books", list.get(0).getString("id"));
        assertEquals("driving reaches further", 2, leaving(places, weights, -300, true, null).size());
    }

    @Test
    public void afterLeavingSkipsPlacesClosingSoon() throws Exception {
        JSONObject weights = new JSONObject().put("books", 3);
        JSONObject shop = place("books", "Boulder Book Store", "Book store", "books", 4.8, 1900, 400);
        JSONArray periods = new JSONArray();
        for (int d = 0; d < 7; d++) periods.put(new JSONObject().put("open", new JSONObject().put("day", d).put("hour", 9).put("minute", 0))
                .put("close", new JSONObject().put("day", d).put("hour", 17).put("minute", 0)));
        shop.getJSONObject("hours").put("periods", periods);
        assertTrue("closes at 5, it's 4:30", leaving(new JSONArray().put(shop), weights, -300, false, null).isEmpty());
    }

    @Test
    public void todaysPickWinsWhenItsClose() throws Exception {
        JSONObject weights = new JSONObject().put("books", 3);
        JSONArray places = new JSONArray()
                .put(place("books", "Boulder Book Store", "Book store", "books", 4.8, 1900, 400))
                .put(place("pick", "Lucile's", "Restaurant", "food", 4.6, 2100, 700));
        assertEquals("pick", leaving(places, weights, -300, false, "pick").get(0).getString("id"));
        assertEquals("8 min walk", Curator.travel(650, false));
        assertEquals("13 min drive", Curator.travel(8000, true));
    }

    @Test
    public void homeIsWhereYouSleepThreeNights() throws Exception {
        ZoneId z = DENVER;
        long morning = ZonedDateTime.of(2026, 10, 8, 8, 0, 0, 0, z).toInstant().toEpochMilli();
        assertTrue(Home.overnight(morning, 10 * 3_600_000L, z));
        assertFalse("an afternoon at the office", Home.overnight(morning + 10 * 3_600_000L, 9 * 3_600_000L, z));
        JSONArray spots = new JSONArray();
        assertFalse(Home.after(spots, 40.0, -105.0, true));
        assertFalse(Home.after(spots, 40.0005, -105.0, true));
        assertTrue("third night", Home.after(spots, 40.0, -105.0, true));
        assertTrue("leaving home in the afternoon too", Home.after(spots, 40.0, -105.0, false));
        assertFalse("somewhere else", Home.after(spots, 40.1, -105.0, false));
    }
}
