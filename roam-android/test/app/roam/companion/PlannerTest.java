package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PlannerTest {
    static final long DAY = 86_400_000L;
    static final long NOW = 1_780_000_000_000L;

    static final Planner.Sources NONE = new Planner.Sources() {
        @Override
        public JSONArray liveNear(double lat, double lng, long now) {
            return new JSONArray();
        }

        @Override
        public boolean busyBetween(long start, long end) {
            return false;
        }
    };

    static JSONObject homeTask(JSONArray candidates) throws Exception {
        return new JSONObject().put("key", "home:1").put("kind", "home").put("name", "Near home")
                .put("created", NOW - 10 * DAY).put("start", 0).put("end", Long.MAX_VALUE)
                .put("every", 3 * DAY).put("jitter", 0).put("zone", "America/Denver")
                .put("candidates", candidates);
    }

    static JSONObject candidate(String id, double rank) throws Exception {
        return new JSONObject().put("id", id).put("name", id).put("rank", rank).put("body", "Near home · " + id);
    }

    @Test
    public void picksHighestRankedFreshIdea() throws Exception {
        JSONObject planning = new JSONObject().put("tasks", new JSONArray().put(homeTask(
                new JSONArray().put(candidate("a", 10)).put(candidate("b", 30)))));
        Planner.Pick pick = Planner.choose(planning, new JSONObject(), new JSONArray(), false, NOW, NONE);
        assertEquals("b", pick.place.getString("id"));
    }

    @Test
    public void skipsIdeasAlreadySentOrPlanned() throws Exception {
        JSONObject planning = new JSONObject()
                .put("tasks", new JSONArray().put(homeTask(new JSONArray().put(candidate("a", 10)).put(candidate("b", 30)))))
                .put("planned", new JSONObject().put("home:1|a", true));
        JSONObject ledger = new JSONObject().put("home:1", new JSONObject()
                .put("last", NOW - 4 * DAY).put("ids", new JSONArray().put("b")));
        assertNull(Planner.choose(planning, ledger, new JSONArray(), false, NOW, NONE));
    }

    @Test
    public void pausesHomeIdeasWhileAway() throws Exception {
        JSONObject planning = new JSONObject().put("tasks", new JSONArray().put(homeTask(new JSONArray().put(candidate("a", 10)))));
        assertNull(Planner.choose(planning, new JSONObject(), new JSONArray(), true, NOW, NONE));
    }

    @Test
    public void skipsEventsWhenTheCalendarIsBusy() throws Exception {
        JSONObject planning = new JSONObject().put("tasks", new JSONArray().put(homeTask(new JSONArray()
                .put(candidate("concert", 50).put("start", NOW + 2 * DAY))
                .put(candidate("park", 10)))));
        Planner.Sources busy = new Planner.Sources() {
            @Override
            public JSONArray liveNear(double lat, double lng, long now) {
                return new JSONArray();
            }

            @Override
            public boolean busyBetween(long start, long end) {
                return true;
            }
        };
        assertEquals("park", Planner.choose(planning, new JSONObject(), new JSONArray(), false, NOW, busy).place.getString("id"));
    }

    @Test
    public void recordMarksFinalAdvanceIdea() throws Exception {
        JSONObject task = new JSONObject().put("key", "trip:1").put("kind", "advance").put("start", NOW + DAY / 2);
        JSONObject ledger = new JSONObject();
        Planner.record(ledger, task, "x", NOW);
        JSONObject entry = ledger.getJSONObject("trip:1");
        assertTrue(entry.getBoolean("final"));
        assertEquals(NOW, entry.getLong("last"));
    }

    @Test
    public void mergeKeepsNewestLedgerEntry() throws Exception {
        JSONObject mine = new JSONObject().put("k", new JSONObject().put("last", 5));
        JSONObject web = new JSONObject().put("k", new JSONObject().put("last", 9)).put("j", new JSONObject().put("last", 1));
        JSONObject merged = Planner.mergeLedger(mine, web);
        assertEquals(9, merged.getJSONObject("k").getLong("last"));
        assertTrue(merged.has("j"));
    }

    @Test
    public void awayWhenFarFromHome() throws Exception {
        JSONObject planning = new JSONObject().put("home", new JSONObject().put("lat", 40.0).put("lng", -105.0));
        JSONObject nearFix = new JSONObject().put("lat", 40.01).put("lng", -105.0).put("accuracy", 20).put("at", NOW);
        JSONObject farFix = new JSONObject().put("lat", 39.0).put("lng", -108.0).put("accuracy", 20).put("at", NOW);
        assertFalse(Planner.homeAway(planning, nearFix, NOW));
        assertTrue(Planner.homeAway(planning, farFix, NOW));
    }
}
