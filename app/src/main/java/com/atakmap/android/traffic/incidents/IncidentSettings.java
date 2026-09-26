package com.atakmap.android.traffic.incidents;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.drawable.BitmapDrawable;
import android.preference.PreferenceManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.traffic.plugin.R;

import java.util.Locale;

/**
 * The 511 settings page: the zoom gate, and one switch per type of incident.
 *
 * <p>Laid out the way IPAWS's settings page is, so the operator is not learning a new
 * dialect per plugin: a row that names the setting and its value, an arrow that opens
 * its controls, open or closed remembered. The gate reads the live scale bar, the same
 * reference as Feature Layer, IPAWS and Atmosphere.
 *
 * <p>Each type is its own tile -- its map icon, its name, and ON or OFF in Traffic's
 * green and red -- so a type can be switched in one tap and the icon says which pins
 * the tile controls.
 */
public class IncidentSettings {

    /** Feature Layer's presets, in the operator's large unit, as the scale bar reads them. */
    private static final double[] GATE_BIG = { 0.25, 1, 5, 15, 50 };
    private static final int ON = 0xFF3DDC61;
    private static final int OFF = 0xFFFF5B52;

    private final Context pluginContext;
    private final MapView mapView;
    private final IncidentFeed feed;
    private final SharedPreferences prefs;

    private final Fold gateFold;
    private final Fold scopeFold;
    private final Fold typesFold;
    private final android.widget.TextView scopeLabel;
    private final android.widget.SeekBar scopeSeek;
    private final Button scopeFromButton;
    /** True while a finger is on the slider, so a refresh does not yank it back. */
    private boolean scopeDragging;
    /** Radius presets in the large unit, after Everything and What is in view (IPAWS's). */
    private static final int[] SCOPE_PRESETS = { 2, 5, 10, 25, 50 };
    /** The radius a "Measuring from" tap gives when there was none. */
    private static final int DEFAULT_SCOPE_BIG = 25;
    private final Button gateButton;
    private final Button statesButton;
    private final Button[] tiles = new Button[IncidentStyles.KINDS.length];

    /** Atmosphere's drop-down: a titled row, an arrow, a body; open state remembered. */
    private final class Fold {
        final Button head;
        final ImageButton chevron;
        final View body;
        final String pref;
        boolean open;

        Fold(View page, int headId, int chevronId, int bodyId, String pref, boolean dflt) {
            head = page.findViewById(headId);
            chevron = page.findViewById(chevronId);
            body = page.findViewById(bodyId);
            this.pref = pref;
            open = prefs.getBoolean(pref, dflt);
            final View.OnClickListener flip = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open = !open;
                    prefs.edit().putBoolean(Fold.this.pref, open).apply();
                    show();
                }
            };
            head.setOnClickListener(flip);
            chevron.setOnClickListener(flip);
            show();
        }

        void show() {
            chevron.setRotation(open ? 180f : 0f);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
        }
    }

    public IncidentSettings(Context pluginContext, MapView mapView, View page,
            IncidentFeed feed) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.feed = feed;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());

        gateFold = new Fold(page, R.id.fold_gate_head, R.id.fold_gate_chev,
                R.id.fold_gate_body, "traffic.511.fold.gate", true);
        scopeFold = new Fold(page, R.id.fold_scope_head, R.id.fold_scope_chev,
                R.id.fold_scope_body, "traffic.511.fold.scope", true);
        scopeLabel = page.findViewById(R.id.scope_label);
        scopeSeek = page.findViewById(R.id.scope_seek);
        scopeFromButton = page.findViewById(R.id.btn_scope_from);
        bindScope(page);
        typesFold = new Fold(page, R.id.fold_types_head, R.id.fold_types_chev,
                R.id.fold_types_body, "traffic.511.fold.types", true);

        statesButton = page.findViewById(R.id.btn_states);
        statesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseStates();
            }
        });

        gateButton = page.findViewById(R.id.btn_gate);
        gateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseGate();
            }
        });
        page.findViewById(R.id.btn_use_zoom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // What the scale bar reads right now becomes the gate, so "Use this
                // zoom" means exactly this zoom.
                IncidentSettings.this.feed.setGate(ScaleBar.meters(IncidentSettings.this.mapView));
                refresh();
            }
        });

        buildTiles((LinearLayout) page.findViewById(R.id.types_grid));
        refresh();
    }

    /** Two tiles to a row, in Overlay Manager's order. */
    private void buildTiles(LinearLayout grid) {
        final int iconPx = dp(30);
        LinearLayout row = null;
        for (int i = 0; i < IncidentStyles.KINDS.length; i++) {
            final String kind = IncidentStyles.KINDS[i];
            if (i % 2 == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                final LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(4);
                grid.addView(row, rp);
            }
            final Button b = (Button) com.atak.plugins.impl.PluginLayoutInflater.inflate(
                    pluginContext, R.layout.incident_type_tile, null);
            final BitmapDrawable icon = new BitmapDrawable(pluginContext.getResources(),
                    IncidentIcons.bitmap(kind, iconPx));
            icon.setBounds(0, 0, iconPx, iconPx);
            b.setCompoundDrawables(icon, null, null, null);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    feed.setKindShown(kind, !feed.isKindShown(kind));
                    refresh();
                }
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 1)
                lp.leftMargin = dp(6);
            row.addView(b, lp);
            tiles[i] = b;
        }
        // An odd count leaves the last row one tile short; a spacer keeps its width.
        if (IncidentStyles.KINDS.length % 2 == 1 && row != null) {
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.leftMargin = dp(6);
            row.addView(new View(pluginContext), lp);
        }
    }

    /** Paints every control from the feed's own settings. */
    public void refresh() {
        statesButton.setText("States: " + statesLabel(feed));

        final String gate = gateLabel(feed.gateMeters());
        gateFold.head.setText("Zoom gate: " + gate);
        gateButton.setText(gate);

        final String area = scopeText(feed);
        scopeFold.head.setText("Area: " + area);
        scopeLabel.setText(area);
        scopeFromButton.setText("Measuring from: " + fromName(feed.scopeFrom()));
        if (!scopeDragging)
            scopeSeek.setProgress(!feed.isRadius() ? 0
                    : Math.max(1, Math.min(scopeSeek.getMax(), bigOf(feed.scopeRadiusMeters()))));

        int shown = 0;
        for (int i = 0; i < IncidentStyles.KINDS.length; i++) {
            final String kind = IncidentStyles.KINDS[i];
            final boolean on = feed.isKindShown(kind);
            if (on)
                shown++;
            final SpannableStringBuilder sb =
                    new SpannableStringBuilder(IncidentStyles.setName(kind));
            sb.append('\n');
            final int start = sb.length();
            sb.append(on ? "ON" : "OFF");
            sb.setSpan(new ForegroundColorSpan(on ? ON : OFF), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.85f), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tiles[i].setText(sb);
        }
        typesFold.head.setText(shown == IncidentStyles.KINDS.length ? "Types: all"
                : "Types: " + shown + " of " + IncidentStyles.KINDS.length);
    }

    /**
     * The gate in the operator's large unit, and "or closer"; or Always. "15 mi", not
     * ATAK's "15.00 mi": a preset is a round number and should read as one, and "Use
     * this zoom" at a 14.33 mi bar reads "14.3 mi".
     */
    public static String gateLabel(double barMeters) {
        if (barMeters == Double.MAX_VALUE)
            return "Always";
        return gateName(barMeters / Units.bigToMeters(1));
    }

    private static String gateName(double big) {
        String num;
        if (big >= 100)
            num = String.format(Locale.US, "%.0f", big);
        else if (big >= 1)
            num = String.format(Locale.US, "%.1f", big);
        else
            num = String.format(Locale.US, "%.2f", big);
        if (num.contains("."))
            num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
        return num + " " + Units.bigLabel() + " or closer";
    }

    // ---- states ----------------------------------------------------------------------

    /** "California, Minnesota", or "none". */
    public static String statesLabel(IncidentFeed feed) {
        final java.util.Set<String> picked = feed.pickedStates();
        if (picked.isEmpty())
            return "none";
        final StringBuilder sb = new StringBuilder();
        for (String c : picked) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(feed.stateName(c));
        }
        return sb.toString();
    }

    /**
     * IPAWS's picker: every state we publish, ticked for the ones they follow. Only
     * states with data are offered, so nothing on the list is a dead end.
     */
    private void chooseStates() {
        final java.util.Map<String, String> pub = feed.publishedStates();
        if (pub.isEmpty()) {
            android.widget.Toast.makeText(mapView.getContext(),
                    "The state list has not loaded yet", android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        final java.util.List<String> codes = new java.util.ArrayList<>(pub.keySet());
        java.util.Collections.sort(codes, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return pub.get(a).compareToIgnoreCase(pub.get(b));
            }
        });
        final java.util.Set<String> picked = feed.pickedStates();
        final String[] names = new String[codes.size()];
        final boolean[] ticked = new boolean[codes.size()];
        for (int i = 0; i < codes.size(); i++) {
            names[i] = pub.get(codes.get(i));
            ticked[i] = picked.contains(codes.get(i));
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("States")
                .setMultiChoiceItems(names, ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final java.util.Set<String> chosen = new java.util.LinkedHashSet<>();
                        for (int i = 0; i < ticked.length; i++)
                            if (ticked[i])
                                chosen.add(codes.get(i));
                        feed.setPickedStates(chosen);
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- area ------------------------------------------------------------------------

    private static String fromName(String from) {
        return "center".equals(from) ? "Map Center" : "My Location";
    }

    /** Whole large units in a radius, e.g. 25 for 25 mi. */
    private static int bigOf(double meters) {
        return (int) Math.round(meters / Units.bigToMeters(1));
    }

    /**
     * Feature Layer's words: What is in view, or Within 25 mi of My Location. Public so
     * the main pane's status line says the same thing the settings page does.
     */
    public static String scopeText(IncidentFeed feed) {
        if (feed.isEverything())
            return "Everything";
        if (!feed.isRadius())
            return "What is in view";
        final String s = "Within " + bigOf(feed.scopeRadiusMeters()) + " " + Units.bigLabel()
                + " of " + fromName(feed.scopeFrom());
        // Said out loud: a circle quietly drawn around the map center when the operator
        // asked for their own position is the wrong picture looking right.
        return feed.scopeHasNoFix() ? s + " (no GPS fix, measuring from the map center)" : s;
    }

    /**
     * IPAWS's controls, word for word: the slider is the radius and its far left is
     * Everything ("all the way left is all on"), Measuring from swaps the point, Use
     * this extent takes the radius from the map, and Presets is the list, where What
     * is in view lives.
     */
    private void bindScope(View page) {
        scopeSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                if (fromUser)
                    scopeLabel.setText(p == 0 ? "Everything"
                            : "Within " + p + " " + Units.bigLabel() + " of "
                                    + fromName(feed.scopeFrom()));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
                scopeDragging = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                scopeDragging = false;
                applyScope(sb.getProgress(), feed.scopeFrom());
            }
        });
        scopeFromButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Swaps the point. From What is in view it also needs a radius, or the
                // button would change nothing anyone could see.
                final int p = feed.isRadius() ? bigOf(feed.scopeRadiusMeters())
                        : DEFAULT_SCOPE_BIG;
                applyScope(p, "center".equals(feed.scopeFrom()) ? "me" : "center");
            }
        });
        page.findViewById(R.id.btn_scope_extent).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // "What I am looking at", as a radius: center to corner, so the whole
                // visible rectangle is inside the circle.
                final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
                final com.atakmap.coremap.maps.coords.GeoPoint c = mapView.getPoint().get();
                if (b == null || c == null || Double.isNaN(b.getNorth())
                        || Double.isNaN(b.getEast())) {
                    android.widget.Toast.makeText(mapView.getContext(),
                            "The map has no extent yet", android.widget.Toast.LENGTH_SHORT)
                            .show();
                    return;
                }
                final double m = c.distanceTo(
                        new com.atakmap.coremap.maps.coords.GeoPoint(b.getNorth(), b.getEast()));
                final double bigD = m / Units.bigToMeters(1);
                if (bigD > scopeSeek.getMax())
                    android.widget.Toast.makeText(mapView.getContext(), String.format(Locale.US,
                            "That view is wider than %d %s, radius set to the maximum",
                            scopeSeek.getMax(), Units.bigLabel()),
                            android.widget.Toast.LENGTH_SHORT).show();
                applyScope((int) Math.max(1, Math.min(scopeSeek.getMax(), Math.round(bigD))),
                        "center");
            }
        });
        page.findViewById(R.id.btn_scope_presets).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] items = new String[SCOPE_PRESETS.length + 2];
                items[0] = "Everything";
                items[1] = "What is in view";
                int checked = feed.isEverything() ? 0 : feed.isRadius() ? -1 : 1;
                final int now = feed.isRadius() ? bigOf(feed.scopeRadiusMeters()) : -1;
                for (int i = 0; i < SCOPE_PRESETS.length; i++) {
                    items[i + 2] = SCOPE_PRESETS[i] + " " + Units.bigLabel() + " of "
                            + fromName(feed.scopeFrom());
                    if (SCOPE_PRESETS[i] == now)
                        checked = i + 2;
                }
                new AlertDialog.Builder(mapView.getContext())
                        .setTitle("Show incidents within")
                        .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                                if (w == 1) {
                                    feed.setScope("view", 0, feed.scopeFrom());
                                    refresh();
                                } else {
                                    applyScope(w == 0 ? 0 : SCOPE_PRESETS[w - 2],
                                            feed.scopeFrom());
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    /** The slider's value: 0 is Everything, anything else a radius. */
    private void applyScope(int big, String from) {
        if (big <= 0)
            feed.setScope("all", 0, from);
        else
            feed.setScope("radius", Units.bigToMeters(big), from);
        refresh();
    }

    /** Feature Layer's picker, word for word. MapView context: never the plugin's. */
    private void chooseGate() {
        final String[] labels = new String[GATE_BIG.length + 1];
        for (int i = 0; i < GATE_BIG.length; i++)
            labels[i] = gateName(GATE_BIG[i]);
        labels[GATE_BIG.length] = "Always";
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Draw when the scale bar reads")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        feed.setGate(which == GATE_BIG.length ? Double.MAX_VALUE
                                : Units.bigToMeters(GATE_BIG[which]));
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                pluginContext.getResources().getDisplayMetrics()));
    }
}
