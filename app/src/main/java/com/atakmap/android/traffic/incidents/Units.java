package com.atakmap.android.traffic.incidents;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;

/**
 * Distances in whatever units the operator has already told ATAK they want.
 *
 * <p>Feature Layer's {@code Units} and {@code ScaleBar.describe}, carried forward
 * (there is no shared module) and cut to what the zoom gate and distance controls need.
 *
 * <p>ATAK keeps the choice in {@code rab_rng_units_pref}, and the stored value is the
 * {@link Span} constant itself: "0" is {@link Span#ENGLISH}, "1" {@link Span#METRIC},
 * "2" {@link Span#NM}. Note that 0 is English, not metric -- assuming the obvious
 * ordering gets it exactly backwards. Read on each call, because it can change in
 * ATAK's settings while the pane is open.
 */
public final class Units {

    private Units() {
    }

    /** @return one of {@link Span#ENGLISH}, {@link Span#METRIC}, {@link Span#NM} */
    public static int type() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                final SharedPreferences p = PreferenceManager
                        .getDefaultSharedPreferences(mv.getContext());
                return Integer.parseInt(p.getString("rab_rng_units_pref",
                        String.valueOf(Span.ENGLISH)));
            }
        } catch (RuntimeException e) {
            // A malformed preference must not stop the pane drawing.
        }
        return Span.ENGLISH;
    }

    /** The large unit the operator thinks in: miles, kilometers or nautical miles. */
    public static Span bigSpan() {
        switch (type()) {
            case Span.METRIC:
                return Span.KILOMETER;
            case Span.NM:
                return Span.NAUTICALMILE;
            default:
                return Span.MILE;
        }
    }

    public static String bigLabel() {
        switch (type()) {
            case Span.METRIC:
                return "km";
            case Span.NM:
                return "NM";
            default:
                return "mi";
        }
    }

    /** Convert a count of {@link #bigSpan()} units into meters. */
    public static double bigToMeters(double n) {
        try {
            return SpanUtilities.convert(n, bigSpan(), Span.METER);
        } catch (RuntimeException e) {
            return n * 1609.344;
        }
    }
}
