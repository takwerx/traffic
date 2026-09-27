package com.atakmap.android.traffic.incidents;

import android.content.Context;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Which state a point is in, from simplified state outlines shipped in the plugin
 * (assets/states/outlines.json, built from the Census Bureau's TIGERweb by
 * tools/traffic511/build_state_outlines.py in the notes repo).
 *
 * <p>The publisher's boxes are padded so a view just over a border fetches the
 * neighbor too, which is right for fetching and wrong for naming the state under
 * the map: California's box takes in Las Vegas, so over Las Vegas the pane never
 * offered Nevada (S22, 2026-09-26).
 */
final class StateOutlines {

    private static final String TAG = "Traffic511";
    private static final String ASSET = "states/outlines.json";
    /** A point no outline holds (a simplified border, a harbor) takes the nearest within this. */
    private static final double NEAREST_M = 10_000d;

    /** code -> rings, each ring flat {lon0, lat0, lon1, lat1, ...}. */
    private final Map<String, double[][]> rings;
    /** code -> {west, south, east, north} of its rings. */
    private final Map<String, double[]> boxes;

    private StateOutlines(Map<String, double[][]> rings, Map<String, double[]> boxes) {
        this.rings = rings;
        this.boxes = boxes;
    }

    /** Reads the asset; an empty set of outlines when it cannot, never an exception. */
    static StateOutlines load(Context pluginContext) {
        final Map<String, double[][]> rings = new HashMap<>();
        final Map<String, double[]> boxes = new HashMap<>();
        try (InputStream in = pluginContext.getAssets().open(ASSET)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0)
                buf.write(chunk, 0, n);
            final JSONObject all = new JSONObject(buf.toString("UTF-8"));
            for (Iterator<String> it = all.keys(); it.hasNext();) {
                final String code = it.next();
                final JSONArray list = all.getJSONArray(code);
                final double[][] out = new double[list.length()][];
                final double[] box = { 180d, 90d, -180d, -90d };
                for (int r = 0; r < list.length(); r++) {
                    final JSONArray ring = list.getJSONArray(r);
                    final double[] flat = new double[ring.length() * 2];
                    for (int i = 0; i < ring.length(); i++) {
                        final JSONArray pt = ring.getJSONArray(i);
                        final double x = pt.getDouble(0);
                        final double y = pt.getDouble(1);
                        flat[2 * i] = x;
                        flat[2 * i + 1] = y;
                        box[0] = Math.min(box[0], x);
                        box[1] = Math.min(box[1], y);
                        box[2] = Math.max(box[2], x);
                        box[3] = Math.max(box[3], y);
                    }
                    out[r] = flat;
                }
                rings.put(code, out);
                boxes.put(code, box);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "state outlines unreadable, using the boxes: " + e.getMessage());
        }
        return new StateOutlines(rings, boxes);
    }

    boolean isEmpty() {
        return rings.isEmpty();
    }

    /**
     * The state among {@code allowed} that holds {lat, lon}; else the one whose outline
     * passes within {@link #NEAREST_M}; else null.
     */
    String stateAt(double lat, double lon, Collection<String> allowed) {
        for (String code : allowed) {
            final double[] b = boxes.get(code);
            if (b == null || lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3])
                continue;
            if (contains(rings.get(code), lon, lat))
                return code;
        }
        String best = null;
        double bestM = NEAREST_M;
        final double pad = NEAREST_M / 111_320d * 2d;
        for (String code : allowed) {
            final double[] b = boxes.get(code);
            if (b == null || lon < b[0] - pad || lon > b[2] + pad || lat < b[1] - pad
                    || lat > b[3] + pad)
                continue;
            final double d = distanceM(rings.get(code), lon, lat);
            if (d < bestM) {
                bestM = d;
                best = code;
            }
        }
        return best;
    }

    /** Even-odd across every ring, so a lake cut out of a state is not in it. */
    private static boolean contains(double[][] rings, double x, double y) {
        boolean in = false;
        for (double[] r : rings) {
            final int n = r.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                final double xi = r[2 * i], yi = r[2 * i + 1];
                final double xj = r[2 * j], yj = r[2 * j + 1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                    in = !in;
            }
        }
        return in;
    }

    /** Metres from the point to the nearest edge of any ring; equirectangular is plenty. */
    private static double distanceM(double[][] rings, double x, double y) {
        final double k = Math.cos(Math.toRadians(y));
        double best = Double.MAX_VALUE;
        for (double[] r : rings) {
            final int n = r.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                final double ax = (r[2 * j] - x) * k, ay = r[2 * j + 1] - y;
                final double bx = (r[2 * i] - x) * k, by = r[2 * i + 1] - y;
                final double dx = bx - ax, dy = by - ay;
                final double len = dx * dx + dy * dy;
                double t = len == 0 ? 0 : -(ax * dx + ay * dy) / len;
                t = Math.max(0, Math.min(1, t));
                final double px = ax + t * dx, py = ay + t * dy;
                best = Math.min(best, Math.sqrt(px * px + py * py));
            }
        }
        return best * 111_320d;
    }
}
