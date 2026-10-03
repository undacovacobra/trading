package app.roam.companion;

import java.util.Locale;

/** Distances in the units people use where they are. */
public final class Units {
    private Units() {}

    static boolean usesMiles(Locale locale) {
        String c = locale.getCountry();
        return "US".equals(c) || "GB".equals(c) || "LR".equals(c) || "MM".equals(c);
    }

    public static String distance(double meters, Locale locale) {
        if (usesMiles(locale)) return String.format(Locale.US, "%.1f miles", meters / 1609.344);
        if (meters < 1000) return String.format(Locale.US, "%d m", Math.round(meters / 10) * 10);
        return String.format(Locale.US, "%.1f km", meters / 1000);
    }
}
