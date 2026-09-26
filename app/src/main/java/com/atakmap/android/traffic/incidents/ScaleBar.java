package com.atakmap.android.traffic.incidents;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.AbstractParentWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.ScaleWidget;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.log.Log;

/**
 * Reads the same number as ATAK's scale bar in the lower left.
 *
 * <p>The point is to give the operator one reference instead of two. A zoom threshold
 * expressed in meters per pixel, or as an invented band like "county level", is a
 * second scale they have to learn and reconcile against the bar already on screen.
 * Quoting the bar means the panel and the map agree by construction.
 *
 * <p>ATAK's {@code ScaleWidget} implements the stable {@code IScaleWidget2} interface,
 * but there is no stable way to <em>find</em> it — the widget tree is reached through
 * {@code MapView.getComponentExtra("rootLayoutWidget")}, which is internal and
 * obfuscated, and CLAUDE.md is explicit that internals decide whether a plugin
 * survives an ATAK upgrade. So the lookup is best-effort and cached, and there is a
 * pure-arithmetic fallback that is close enough to be useful if the widget ever moves.
 */
public final class ScaleBar {

    private static final String TAG = "Traffic511";

    /** Roughly the bar's own width; only used by the fallback. */
    private static final double FALLBACK_BAR_PIXELS = 200;

    private static ScaleWidget cached;
    private static boolean lookupFailed;

    private ScaleBar() {
    }

    private static ScaleWidget widget(MapView mv) {
        if (cached != null || lookupFailed || mv == null)
            return cached;
        try {
            final Object root = mv.getComponentExtra("rootLayoutWidget");
            if (root instanceof AbstractParentWidget)
                cached = find((AbstractParentWidget) root, 0);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "scale widget lookup failed; using arithmetic instead", e);
        }
        if (cached == null)
            lookupFailed = true;        // do not re-walk the tree on every frame
        return cached;
    }

    private static ScaleWidget find(AbstractParentWidget parent, int depth) {
        if (depth > 6)
            return null;                // the tree is shallow; this is a cycle guard
        for (int i = 0; i < parent.getChildCount(); i++) {
            final MapWidget w = parent.getChildAt(i);
            if (w instanceof ScaleWidget)
                return (ScaleWidget) w;
            if (w instanceof AbstractParentWidget) {
                final ScaleWidget found = find((AbstractParentWidget) w, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /**
     * What the scale bar currently reads, e.g. {@code "5 mi"}.
     *
     * @return the bar's own text, or an equivalent computed value if it cannot be read
     */
    public static String text(MapView mv) {
        final ScaleWidget w = widget(mv);
        if (w != null) {
            try {
                final String t = w.getText();
                if (t != null && !t.trim().isEmpty())
                    return t.trim();
            } catch (RuntimeException e) {
                // fall through to arithmetic
            }
        }
        return approximate(mv == null ? 0 : mv.getMapResolution());
    }

    /** The distance the bar spans, in meters — what a threshold is compared against. */
    public static double meters(MapView mv) {
        final ScaleWidget w = widget(mv);
        if (w != null) {
            try {
                final double s = w.getScale();
                if (s > 0)
                    return s;
            } catch (RuntimeException e) {
                // fall through
            }
        }
        return (mv == null ? 1 : mv.getMapResolution()) * FALLBACK_BAR_PIXELS;
    }

    /** Format a bar-width distance the way ATAK would, in the operator's units. */
    public static String describe(double barMeters) {
        try {
            return SpanUtilities.formatType(Units.type(), barMeters, Span.METER);
        } catch (RuntimeException e) {
            return Math.round(barMeters) + " m";
        }
    }

    private static String approximate(double metersPerPixel) {
        return describe(metersPerPixel * FALLBACK_BAR_PIXELS);
    }
}
