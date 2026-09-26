package com.atakmap.android.traffic.incidents;

import com.atakmap.map.layer.feature.style.BasicPointStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;

/**
 * What each kind of incident looks like, and which Overlay Manager switch it sits under.
 *
 * <p>Points are {@link IncidentIcons}: the kind's symbol on a dark disc. Every point also
 * carries an empty label, because ATAK otherwise draws a point feature's name beside
 * it, and a statewide map of CHP headlines is unreadable.
 */
public final class IncidentStyles {

    private IncidentStyles() {
    }

    /** Order is the order of the sets in Overlay Manager. */
    public static final String[] KINDS = {
            Incident.CRASH, Incident.CLOSURE, Incident.LANES, Incident.ROADWORK,
            Incident.HAZARD, Incident.FIRE, Incident.CHAINS, Incident.WEATHER,
            Incident.SIGN, Incident.INFO,
    };

    /** The kind's color: its line along the road, and the heading on its details. */
    public static int color(String kind) {
        switch (kind) {
            case Incident.CRASH:
            case Incident.CLOSURE:
                return 0xFFFF3B30;
            case Incident.LANES:
            case Incident.ROADWORK:
            case Incident.FIRE:
                return 0xFFFF7A00;
            case Incident.HAZARD:
                return 0xFFFFD60A;
            case Incident.SIGN:
                return 0xFFFFB300;
            case Incident.CHAINS:
            case Incident.WEATHER:
            case Incident.INFO:
            default:
                return 0xFF4FC3F7;
        }
    }

    /** The Overlay Manager name for a kind, and the heading on its details. */
    public static String setName(String kind) {
        switch (kind) {
            case Incident.CRASH:
                return "Crashes";
            case Incident.CLOSURE:
                return "Road closed";
            case Incident.LANES:
                return "Lanes closed";
            case Incident.ROADWORK:
                return "Road work";
            case Incident.HAZARD:
                return "Hazards";
            case Incident.FIRE:
                return "Vehicle fires";
            case Incident.CHAINS:
                return "Chains and snow";
            case Incident.WEATHER:
                return "Weather";
            case Incident.SIGN:
                return "Message signs";
            default:
                return "Notices";
        }
    }

    /**
     * The kind's icon, and an empty label so ATAK does not print the headline beside
     * every pin. Falls back to a plain dot when the icon could not be written.
     */
    public static Style point(String kind, String iconUri) {
        final Style mark = iconUri != null
                ? new IconPointStyle(0xFFFFFFFF, iconUri, IncidentIcons.DP,
                        IncidentIcons.DP, 0, 0, 0f, false)
                : new BasicPointStyle(color(kind), 18f);
        return new CompositeStyle(new Style[] {
                mark,
                new LabelPointStyle("", 0, 0, LabelPointStyle.ScrollMode.OFF),
        });
    }

    /**
     * One stroke. A composite of two point styles drew only the first; until a line
     * source is on a device to prove two strokes stack, do not assume they do.
     */
    public static Style line(String kind) {
        return new BasicStrokeStyle(color(kind), 5f);
    }
}
