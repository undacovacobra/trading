package app.roam.companion;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;

/**
 * Roam's small taste vocabulary, and how Google place types and OpenStreetMap tags map onto it.
 * The web app learns a weight per tag from what you save, skip and visit.
 */
final class PlaceTags {
    private static final Map<String, String[]> GOOGLE = new HashMap<String, String[]>();
    private static final Map<String, String[]> OSM = new HashMap<String, String[]>();
    private static final Map<String, String> OSM_LABEL = new HashMap<String, String>();

    static {
        g("cafe", "coffee");
        g("coffee_shop", "coffee");
        g("tea_house", "coffee", "quiet");
        g("bakery", "sweet");
        g("ice_cream_shop", "sweet");
        g("dessert_shop", "sweet");
        g("restaurant", "food");
        g("bar", "drinks");
        g("pub", "drinks");
        g("wine_bar", "drinks");
        g("night_club", "nightlife");
        g("park", "outdoors");
        g("national_park", "outdoors", "trails", "views");
        g("hiking_area", "outdoors", "trails");
        g("garden", "outdoors", "gardens");
        g("botanical_garden", "outdoors", "gardens");
        g("observation_deck", "views");
        g("museum", "culture");
        g("art_gallery", "culture");
        g("performing_arts_theater", "culture", "music");
        g("historical_landmark", "culture", "sights");
        g("tourist_attraction", "sights");
        g("book_store", "books", "quiet");
        g("library", "books", "quiet");
        g("zoo", "fun");
        g("aquarium", "fun");
        g("bowling_alley", "fun");
        g("amusement_park", "fun");
        g("spa", "wellness");

        o("amenity=cafe", "Café", "coffee");
        o("amenity=restaurant", "Restaurant", "food");
        o("amenity=fast_food", "Quick bite", "food");
        o("amenity=food_court", "Food hall", "food");
        o("amenity=ice_cream", "Ice cream", "sweet");
        o("amenity=bar", "Bar", "drinks");
        o("amenity=pub", "Pub", "drinks");
        o("amenity=biergarten", "Beer garden", "drinks");
        o("amenity=nightclub", "Nightclub", "nightlife");
        o("amenity=theatre", "Theatre", "culture", "music");
        o("amenity=arts_centre", "Arts centre", "culture");
        o("amenity=cinema", "Cinema", "culture");
        o("amenity=library", "Library", "books", "quiet");
        o("amenity=marketplace", "Market", "food", "sights");
        o("shop=bakery", "Bakery", "sweet");
        o("shop=books", "Bookshop", "books", "quiet");
        o("tourism=museum", "Museum", "culture");
        o("tourism=gallery", "Gallery", "culture");
        o("tourism=viewpoint", "Viewpoint", "views", "outdoors");
        o("tourism=attraction", "Attraction", "sights");
        o("tourism=zoo", "Zoo", "fun");
        o("tourism=aquarium", "Aquarium", "fun");
        o("tourism=theme_park", "Theme park", "fun");
        o("leisure=park", "Park", "outdoors");
        o("leisure=garden", "Garden", "outdoors", "gardens");
        o("leisure=nature_reserve", "Nature reserve", "outdoors", "trails");
        o("leisure=bowling_alley", "Bowling", "fun");
        o("leisure=escape_game", "Escape room", "fun");
        o("leisure=miniature_golf", "Mini golf", "fun");
        o("leisure=sauna", "Sauna", "wellness");
        o("leisure=spa", "Spa", "wellness");
        o("leisure=sports_centre", "Sports centre", "active");
        o("leisure=fitness_centre", "Gym", "active");
        o("sport=climbing", "Climbing", "active");
        o("highway=trailhead", "Trailhead", "trails", "outdoors");
        o("historic=monument", "Monument", "culture", "sights");
        o("historic=castle", "Castle", "culture", "sights");
        o("historic=ruins", "Ruins", "culture", "sights");
        o("natural=hot_spring", "Hot spring", "wellness", "outdoors");
        o("natural=beach", "Beach", "outdoors");
    }

    private PlaceTags() {}

    private static void g(String type, String... tags) {
        GOOGLE.put(type, tags);
    }

    private static void o(String keyValue, String label, String... tags) {
        OSM.put(keyValue, tags);
        OSM_LABEL.put(keyValue, label);
    }

    /** Every OSM key=value Roam asks Overpass for. */
    static Set<String> osmKeys() {
        return OSM.keySet();
    }

    static Set<String> fromGoogle(JSONArray types) {
        Set<String> tags = new LinkedHashSet<String>();
        if (types == null) return tags;
        for (int i = 0; i < types.length(); i++) {
            String t = types.optString(i);
            String[] mapped = GOOGLE.get(t);
            if (mapped == null && t.endsWith("_restaurant")) mapped = GOOGLE.get("restaurant");
            if (mapped != null) for (String m : mapped) tags.add(m);
        }
        return tags;
    }

    static Set<String> fromOsm(String key, String value) {
        Set<String> tags = new LinkedHashSet<String>();
        String[] mapped = OSM.get(key + "=" + value);
        if (mapped != null) for (String m : mapped) tags.add(m);
        return tags;
    }

    static String osmLabel(String key, String value) {
        return OSM_LABEL.get(key + "=" + value);
    }
}
