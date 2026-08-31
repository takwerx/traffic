package com.atakmap.android.traffic.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.traffic.LiveOverlay;
import com.atakmap.coremap.log.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Traffic — live tiles that keep refreshing over the operator's own base map.
 *
 * <p>Pick a source, turn it on, and it draws above whatever base map is already selected
 * and keeps itself current while the map sits untouched. The engine is
 * {@link LiveOverlay}; this class is the four controls in front of it.
 */
public class Traffic implements IPlugin, LiveOverlay.Listener {

    private static final String TAG = "Traffic";

    private final IServiceController serviceController;
    private static final String PREFS_KEY = "traffic_preferences";

    private Context pluginContext;
    private IHostUIService uiService;
    private ToolbarItem toolbarItem;
    private Pane pane;

    private LiveOverlay overlay;

    /** What the source button offers. Order is the order they are shown. */
    private final List<LiveOverlay.Source> sources = new ArrayList<>();
    private LiveOverlay.Source chosen;

    /** Offered intervals, in seconds. */
    private static final int[] INTERVAL_CHOICES = { 15, 30, 60, 120, 300, 600 };

    private Button sourceButton;
    private Button toggleButton;
    private Button refreshButton;
    private Button intervalButton;
    private TextView status;

    private final SimpleDateFormat clock =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    public Traffic(IServiceController serviceController) {
        this.serviceController = serviceController;

        final PluginContextProvider ctxProvider =
                serviceController.getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        // One source today. The list stays a list because the mechanism is not specific
        // to traffic — anything with a short life (weather radar, fire perimeters) is the
        // same problem — and because the source chooser costs nothing until there are two.
        // The operator-facing name is deliberately the capability, not the provider: the
        // provider is recorded in the source XML and disclosed in the README, which is
        // where a reviewer looks for it, rather than on a button.
        sources.add(new LiveOverlay.Source("traffic",
                "Traffic Overlay",
                "mapsources/traffic.xml", 60000L));
        chosen = sources.get(0);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        registerPreferences();

        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        // Leaving a heartbeat running against a torn-down map view is how a plugin wedges
        // the renderer, so the overlay comes down with the plugin.
        if (overlay != null) {
            overlay.turnOff();
            overlay = null;
        }
        unregisterPreferences();

        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
    }

    /**
     * Puts Traffic into ATAK's Tool Preferences, which is the only route to the
     * user manual. The PDF is built into the plugin's assets, and an asset is
     * not reachable by anyone -- without this entry it ships inside the APK
     * with no way to open it, which is what 0.2 did.
     *
     * Guarded rather than assumed: a build that does not expose
     * {@code ToolsPreferenceFragment} should lose the manual, not the plugin.
     */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment
                            .ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(
                                    R.drawable.ic_toolbar),
                            new TrafficPreferenceFragment(pluginContext)));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not register preferences: " + notThisBuild);
        }
    }

    private void unregisterPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment
                    .unregister(PREFS_KEY);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not unregister preferences: " + notThisBuild);
        }
    }

    // ------------------------------------------------------------------------ pane

    private void showPane() {
        if (pane == null) {
            final View v = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);

            sourceButton = v.findViewById(R.id.btn_source);
            // A chooser offering one choice is furniture. Hide it until there are two.
            if (sources.size() < 2) {
                sourceButton.setVisibility(View.GONE);
                final View heading = v.findViewById(R.id.source_heading);
                if (heading != null)
                    heading.setVisibility(View.GONE);
            }
            toggleButton = v.findViewById(R.id.btn_toggle);
            refreshButton = v.findViewById(R.id.btn_refresh);
            intervalButton = v.findViewById(R.id.btn_interval);
            status = v.findViewById(R.id.status);

            sourceButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pickSource();
                }
            });
            toggleButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toggle();
                }
            });
            refreshButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (overlay != null)
                        overlay.refreshNow();
                }
            });
            intervalButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pickInterval();
                }
            });

            pane = new PaneBuilder(v)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();

            render();
        }

        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    // --------------------------------------------------------------------- actions

    private void toggle() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null) {
            say("No map view — cannot draw an overlay.");
            return;
        }
        if (overlay == null) {
            overlay = new LiveOverlay(mapView, pluginContext);
            overlay.setListener(this);
        }

        if (overlay.isOn()) {
            overlay.turnOff();
            return;
        }

        final String failure = overlay.turnOn(chosen);
        if (failure != null)
            say(chosen.label + " did not load.\n\n" + failure);
    }

    private void pickSource() {
        final String[] labels = new String[sources.size()];
        for (int i = 0; i < sources.size(); i++)
            labels[i] = sources.get(i).label;

        // The MapView context, never the plugin context: a dialog built on the plugin
        // context has no window token and throws BadTokenException, taking ATAK with it.
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle(pluginContext.getString(R.string.pick_source))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final LiveOverlay.Source picked = sources.get(which);
                        final boolean wasOn = overlay != null && overlay.isOn();
                        chosen = picked;
                        if (wasOn) {
                            final String failure = overlay.turnOn(picked);
                            if (failure != null)
                                say(picked.label + " did not load.\n\n" + failure);
                        }
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickInterval() {
        final String[] labels = new String[INTERVAL_CHOICES.length];
        for (int i = 0; i < INTERVAL_CHOICES.length; i++) {
            final int s = INTERVAL_CHOICES[i];
            labels[i] = s < 60 ? s + " seconds"
                    : (s / 60) + (s == 60 ? " minute" : " minutes");
        }

        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle(pluginContext.getString(R.string.pick_interval))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (overlay != null)
                            overlay.setIntervalMs(INTERVAL_CHOICES[which] * 1000L);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------------- status

    @Override
    public void onOverlayChanged(final LiveOverlay o) {
        final MapView mapView = MapView.getMapView();
        if (mapView == null)
            return;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                render();
            }
        });
    }

    /** Paint the controls from the overlay's actual state, never from what was asked. */
    private void render() {
        if (sourceButton == null)
            return;

        sourceButton.setText(chosen != null ? chosen.label
                : pluginContext.getString(R.string.source_none));

        final boolean on = overlay != null && overlay.isOn();
        toggleButton.setText(on ? R.string.turn_off : R.string.turn_on);
        refreshButton.setEnabled(on);
        intervalButton.setEnabled(on);

        if (!on) {
            status.setText(state(R.string.state_off, R.color.state_off));
            return;
        }

        final long every = overlay.getIntervalMs() / 1000L;
        final SpannableStringBuilder sb =
                new SpannableStringBuilder(state(R.string.state_on, R.color.state_on));
        sb.append("\n");
        if (overlay.isHoldingForScreen())
            sb.append("Holding while the screen is off; refreshes on wake.\n");
        else
            sb.append("Refreshing every ").append(String.valueOf(every)).append("s.\n");

        // "Last refresh" means the moment tiles actually changed, not the moment the
        // plugin asked for them. The difference only shows up when something is wrong —
        // which is exactly when an operator is reading this line.
        final long changed = overlay.getLastTileChangeAt();
        if (!overlay.isFreshnessKnown()) {
            sb.append("This source keeps no cache, so freshness cannot be shown.");
        } else if (changed > 0) {
            // Say why the tiles are current when the reason is the operator picking the
            // device up. Otherwise all they see is a recent time and have to work it out.
            if (overlay.isFreshFromWake())
                sb.append(pluginContext.getString(R.string.woke_fmt,
                        clock.format(new Date(changed))));
            else
                sb.append("Last refresh ").append(clock.format(new Date(changed)));
            if (overlay.isStale())
                sb.append("\nNo new tiles since then — check the network.");
        } else if (overlay.isHoldingForScreen()) {
            sb.append("No tiles yet.");
        } else {
            sb.append("Waiting for the first tiles…");
        }
        status.setText(sb);
    }

    /**
     * The state word, in its colour and larger than the detail beneath it.
     *
     * ON and OFF are what the operator reads first and often the only thing they read, so
     * the word carries the answer by itself rather than needing the sentence after it.
     */
    private CharSequence state(int wordRes, int colorRes) {
        final SpannableStringBuilder s =
                new SpannableStringBuilder(pluginContext.getString(wordRes));
        final int end = s.length();
        s.setSpan(new ForegroundColorSpan(
                        pluginContext.getResources().getColor(colorRes)),
                0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(1.4f), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void say(String message) {
        try {
            new AlertDialog.Builder(MapView.getMapView().getContext())
                    .setTitle(pluginContext.getString(R.string.app_name))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, message, t);
        }
    }
}
