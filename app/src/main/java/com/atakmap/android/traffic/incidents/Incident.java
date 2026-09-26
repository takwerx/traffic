package com.atakmap.android.traffic.incidents;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One road incident as our 511 server publishes it.
 *
 * <p>The server has already turned every state's format into this one, so there is
 * exactly one parser here and no per-state code in the plugin: a state changing its
 * feed is fixed on the server the same day, never through a tak.gov round trip.
 *
 * <p>Everything is checked on the way in. The file comes from our own host, but a
 * plugin running inside ATAK does not get to trust a network answer with the host's
 * process: a bad coordinate or a runaway string is dropped here, not drawn.
 */
public final class Incident {

    // The server's kinds, one icon each (see IncidentIcons).
    public static final String CRASH = "crash";
    /** The road is closed outright. */
    public static final String CLOSURE = "closure";
    /** Some lanes closed, the road still open. */
    public static final String LANES = "lanes";
    public static final String ROADWORK = "roadwork";
    public static final String HAZARD = "hazard";
    public static final String FIRE = "fire";
    /** Chain control, snow and ice. */
    public static final String CHAINS = "chains";
    public static final String WEATHER = "weather";
    /** A changeable message sign and what it is showing. */
    public static final String SIGN = "sign";
    /** Advisories, planned events, anything else. */
    public static final String INFO = "info";

    private static final int MAX_TEXT = 2000;
    private static final int MAX_DETAILS = 8000;
    private static final int MAX_POINTS = 20000;

    public final String id;
    public final String kind;
    public final String headline;
    public final String where;
    public final String area;
    public final String agency;
    public final String status;
    public final String reported;
    public final String updated;
    public final String details;
    /** The agency's unit updates, newest first: "11:33 At scene". Empty when none. */
    public final String units;

    /**
     * Longitude, latitude pairs: one pair for a point, one array per line otherwise.
     * Never empty.
     */
    public final List<double[]> parts;
    public final boolean point;

    private Incident(JSONObject p, List<double[]> parts, boolean point) {
        this.id = text(p, "id", 200);
        this.kind = kindOf(text(p, "kind", 40));
        this.headline = text(p, "headline", MAX_TEXT);
        this.where = text(p, "where", MAX_TEXT);
        this.area = text(p, "area", MAX_TEXT);
        this.agency = text(p, "agency", 200);
        this.status = text(p, "status", 200);
        this.reported = text(p, "reported", 40);
        this.updated = text(p, "updated", 40);
        this.details = text(p, "details", MAX_DETAILS);
        this.units = text(p, "units", MAX_TEXT);
        this.parts = Collections.unmodifiableList(parts);
        this.point = point;
    }

    /** One GeoJSON feature, or null when it cannot be drawn. */
    public static Incident parse(JSONObject feature) {
        if (feature == null)
            return null;
        final JSONObject props = feature.optJSONObject("properties");
        final JSONObject geom = feature.optJSONObject("geometry");
        if (props == null || geom == null)
            return null;
        final String type = geom.optString("type", "");
        final JSONArray c = geom.optJSONArray("coordinates");
        if (c == null)
            return null;
        final List<double[]> parts = new ArrayList<>();
        boolean point = false;
        switch (type) {
            case "Point": {
                final double[] xy = pair(c);
                if (xy == null)
                    return null;
                parts.add(xy);
                point = true;
                break;
            }
            case "LineString": {
                final double[] line = line(c);
                if (line == null)
                    return null;
                parts.add(line);
                break;
            }
            case "MultiLineString": {
                for (int i = 0; i < c.length() && i < 200; i++) {
                    final double[] line = line(c.optJSONArray(i));
                    if (line != null)
                        parts.add(line);
                }
                if (parts.isEmpty())
                    return null;
                break;
            }
            default:
                return null;
        }
        final Incident in = new Incident(props, parts, point);
        return in.id.isEmpty() ? null : in;
    }

    /**
     * Everything drawn or shown for this incident, as one string: when it changes, the
     * feature is updated; when it does not, the store is not touched.
     */
    public String signature() {
        final StringBuilder sb = new StringBuilder(256);
        sb.append(kind).append('|').append(headline).append('|').append(where)
                .append('|').append(area).append('|').append(agency).append('|')
                .append(status).append('|').append(reported).append('|').append(updated)
                .append('|').append(details).append('|').append(units);
        for (double[] part : parts) {
            sb.append('|');
            for (double v : part)
                sb.append(v).append(',');
        }
        return sb.toString();
    }

    /** What the pin says on the map and at the top of the details. */
    public String title() {
        return headline.isEmpty() ? "Road incident" : headline;
    }

    private static double[] pair(JSONArray a) {
        if (a == null || a.length() < 2)
            return null;
        final double lon = a.optDouble(0, Double.NaN);
        final double lat = a.optDouble(1, Double.NaN);
        if (!(lon >= -180d && lon <= 180d && lat >= -90d && lat <= 90d))
            return null;
        // 0,0 is what a feed writes when it has no position; it is never a road.
        if (lon == 0d && lat == 0d)
            return null;
        return new double[] { lon, lat };
    }

    private static double[] line(JSONArray a) {
        if (a == null || a.length() < 2 || a.length() > MAX_POINTS)
            return null;
        final double[] out = new double[a.length() * 2];
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            final double[] xy = pair(a.optJSONArray(i));
            if (xy == null)
                continue;
            out[n++] = xy[0];
            out[n++] = xy[1];
        }
        if (n < 4)
            return null;
        if (n == out.length)
            return out;
        final double[] trimmed = new double[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static String kindOf(String k) {
        switch (k) {
            case CRASH:
            case CLOSURE:
            case LANES:
            case ROADWORK:
            case HAZARD:
            case FIRE:
            case CHAINS:
            case WEATHER:
            case SIGN:
                return k;
            case "routine":
                // Travel-time and reminder signs were their own kind for a day; the
                // operator wanted every lit sign as one (2026-09-26).
                return SIGN;
            default:
                return INFO;
        }
    }

    private static String text(JSONObject o, String key, int max) {
        final Object v = o.opt(key);
        if (!(v instanceof String))
            return "";
        final String s = ((String) v).trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
