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
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.traffic.LiveOverlay;
import com.atakmap.android.traffic.incidents.IncidentDetails;
import com.atakmap.android.traffic.incidents.IncidentFeed;
import com.atakmap.android.traffic.incidents.IncidentSettings;
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
public class Traffic implements IPlugin, LiveOverlay.Listener, IncidentFeed.Listener {

    private static final String TAG = "Traffic";

    private final IServiceController serviceController;
    private static final String PREFS_KEY = "traffic_preferences";

    private Context pluginContext;
    private IHostUIService uiService;
    private ToolbarItem toolbarItem;
    private Pane pane;

    private LiveOverlay overlay;

    /**
     * The 511 layer: road incidents beside the tiles, with its own switch. Separate
     * from {@link #overlay} on purpose -- either can be on without the other.
     */
    private IncidentFeed incidents;
    private IncidentDetails incidentDetails;

    /** What the source button offers. Order is the order they are shown. */
    private final List<LiveOverlay.Source> sources = new ArrayList<>();
    private LiveOverlay.Source chosen;

    /** Offered intervals, in seconds. */
    private static final int[] INTERVAL_CHOICES = { 15, 30, 60, 120, 300, 600 };

    /**
     * Whether the overlay was on when ATAK last stopped, and which source it was
     * showing.
     *
     * On the MapView context, not the plugin's: plugin-context preferences do not
     * survive the plugin being reloaded, so a state remembered there would be
     * forgotten by the next update -- which is the one thing this is for.
     *
     * The source is stored by its id rather than its position, so adding or
     * reordering sources later cannot silently select a different one.
     */
    private static final String PREF_ON = "traffic.overlayOn";
    private static final String PREF_SOURCE = "traffic.sourceId";

    /**
     * Whether the operator wants the overlay back after an ATAK restart.
     *
     * Opt-in, and off by default. Restoring means the feed starts fetching the
     * moment ATAK launches, which is not a decision to make on someone's behalf
     * on a metered connection or a cold start in the field.
     */
    private static final String PREF_PERSIST = "traffic.persist";
    /** Whether 511 was on when ATAK last stopped; restored under the same opt-in. */
    private static final String PREF_INCIDENTS_ON = "traffic.incidentsOn";

    private Button sourceButton;
    private Button toggleButton;
    private Button persistButton;
    private Button refreshButton;
    private Button intervalButton;
    private TextView status;
    private Button incidentsButton;
    private TextView incidentsStatus;
    private Button incidentsAddState;
    private View mainPage;
    private View incidentsPage;
    /** Where the main page was scrolled when the settings page replaced it. */
    private int mainScrollY;
    private IncidentSettings incidentSettings;

    private final SimpleDateFormat clock =
            new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final SimpleDateFormat shortClock =
            new SimpleDateFormat("HH:mm", Locale.US);

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
                "mapsources/traffic.xml", 60000L,
                // Highway shields above the traffic, so the colored line does not
                // cover the base map's route numbers (operator, 2026-09-26).
                "mapsources/traffic-labels.xml"));
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
        startIncidents();
        restore();

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
        stopIncidents();
        unregisterPreferences();

        if (uiService == null)
            return;
        // Close our pane on the way out. ATAK leaves an open pane on screen when the
        // plugin unloads -- a reinstall does exactly that -- and its buttons still
        // point at this unloaded instance: 511 ON then answered "no map view" while
        // the freshly loaded plugin sat there working (s10-dev-2, 2026-09-26).
        try {
            if (pane != null && uiService.isPaneVisible(pane))
                uiService.closePane(pane);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not close the pane: " + e);
        }
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
        startIncidents();
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
            incidentsButton = v.findViewById(R.id.btn_incidents);
            incidentsStatus = v.findViewById(R.id.status_incidents);
            incidentsAddState = v.findViewById(R.id.btn_incidents_add_state);
            incidentsAddState.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    final String code = incidents == null ? null : incidents.suggestedState();
                    if (code != null)
                        incidents.addState(code);
                    render();
                }
            });
            incidentsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toggleIncidents();
                }
            });
            mainPage = v.findViewById(R.id.main_page);
            incidentsPage = v.findViewById(R.id.incidents_page);
            if (incidents != null)
                incidentSettings = new IncidentSettings(pluginContext,
                        MapView.getMapView(), incidentsPage, incidents);
            v.findViewById(R.id.btn_incidents_settings).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            showIncidentSettings(true);
                        }
                    });
            v.findViewById(R.id.btn_incidents_back).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            showIncidentSettings(false);
                        }
                    });

            sourceButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pickSource();
                }
            });
            persistButton = v.findViewById(R.id.btn_persist);
            persistButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View unused) {
                    final android.content.SharedPreferences pr = prefs();
                    if (pr == null)
                        return;
                    final boolean on = !pr.getBoolean(PREF_PERSIST, false);
                    pr.edit().putBoolean(PREF_PERSIST, on).apply();
                    // Turning it on should capture what is on the map now,
                    // rather than waiting for the next deliberate toggle.
                    if (on) {
                        remember(overlay != null && overlay.isOn());
                        rememberIncidents(incidents != null && incidents.isOn());
                    }
                    render();
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
                    if (overlay != null && overlay.isOn())
                        overlay.refreshNow();
                    if (incidents != null && incidents.isOn())
                        incidents.refreshNow();
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
            remember(false);
            return;
        }

        final String failure = overlay.turnOn(chosen);
        if (failure != null)
            say(chosen.label + " did not load.\n\n" + failure);
        // Recorded from what the overlay actually did, not from what was asked,
        // so a source that failed to load is not restored as on next launch.
        remember(overlay.isOn());
    }

    /** Stores the overlay's state so the next launch can come back to it. */
    private void remember(boolean on) {
        final android.content.SharedPreferences prefs = prefs();
        if (prefs == null)
            return;
        prefs.edit()
                .putBoolean(PREF_ON, on)
                .putString(PREF_SOURCE, chosen == null ? "" : chosen.id)
                .apply();
    }

    /**
     * Puts the overlay back the way the operator left it.
     *
     * Silent about failure on purpose: this runs while ATAK is starting, and a
     * dialog or a toast about a feed that did not load is noise at the moment
     * the operator is looking at the map, not at this plugin. The stored state
     * is left alone so the next deliberate toggle still knows what was wanted.
     */
    private void restore() {
        final android.content.SharedPreferences prefs = prefs();
        if (prefs == null || !prefs.getBoolean(PREF_PERSIST, false)
                || !prefs.getBoolean(PREF_ON, false))
            return;

        final String id = prefs.getString(PREF_SOURCE, "");
        for (final LiveOverlay.Source s : sources) {
            if (s.id.equals(id)) {
                chosen = s;
                break;
            }
        }

        final MapView mapView = MapView.getMapView();
        if (mapView == null) {
            Log.w(TAG, "no map view at startup; overlay not restored");
            return;
        }
        if (overlay == null) {
            overlay = new LiveOverlay(mapView, pluginContext);
            overlay.setListener(this);
        }
        final String failure = overlay.turnOn(chosen);
        if (failure != null)
            Log.w(TAG, "could not restore " + chosen.label + ": " + failure);
    }

    // ------------------------------------------------------------------- 511

    private void startIncidents() {
        if (incidents != null)
            return;
        final MapView mapView = MapView.getMapView();
        if (mapView == null) {
            // Can happen at a cold ATAK start. The 511 button tries again, so this is
            // a delay, never a dead switch.
            Log.w(TAG, "no map view yet; 511 starts on first use");
            return;
        }
        incidents = new IncidentFeed(mapView, pluginContext);
        incidents.addListener(this);
        incidentDetails = new IncidentDetails(mapView, pluginContext, incidents);
        incidents.addListener(incidentDetails);
        final AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(IncidentDetails.ACTION);
        AtakBroadcast.getInstance().registerReceiver(incidentDetails, filter);

        // 511 comes back the way it was left, across ATAK restarts and plugin updates,
        // with no separate opt-in (operator, 2026-09-26: "should stay on persistent
        // after restart"). The tiles keep their Persistent Overlay switch.
        final android.content.SharedPreferences prefs = prefs();
        if (prefs != null && prefs.getBoolean(PREF_INCIDENTS_ON, false)) {
            final String failure = incidents.turnOn();
            if (failure != null)
                Log.w(TAG, "could not restore 511: " + failure);
        }
    }

    private void stopIncidents() {
        if (incidentDetails != null) {
            incidentDetails.closeIfOpen();
            if (incidents != null)
                incidents.removeListener(incidentDetails);
            try {
                AtakBroadcast.getInstance().unregisterReceiver(incidentDetails);
            } catch (RuntimeException e) {
                Log.w(TAG, "511 details receiver: " + e);
            }
            incidentDetails = null;
        }
        if (incidents != null) {
            incidents.stop();
            incidents = null;
        }
    }

    private void toggleIncidents() {
        startIncidents();
        if (incidents == null) {
            say("No map view -- cannot draw 511 incidents.");
            return;
        }
        if (incidents.isOn()) {
            incidents.turnOff();
        } else {
            final String failure = incidents.turnOn();
            if (failure != null)
                say("511 did not start.\n\n" + failure);
        }
        rememberIncidents(incidents.isOn());
        render();
    }

    /** The 511 settings page in place of the main one, as IPAWS swaps its pages. */
    private void showIncidentSettings(boolean show) {
        if (incidentSettings == null && incidents != null && incidentsPage != null)
            incidentSettings = new IncidentSettings(pluginContext, MapView.getMapView(),
                    incidentsPage, incidents);
        if (mainPage == null || incidentsPage == null || incidentSettings == null)
            return;
        if (show)
            incidentSettings.refresh();
        // Both pages share one scroller, so the settings page opened wherever the
        // main page had been scrolled to: Back and States off the top (S22,
        // 2026-09-26). Open it at the top; Back returns to where the main page was.
        final android.widget.ScrollView scroller = scrollerOf(mainPage);
        if (show && scroller != null)
            mainScrollY = scroller.getScrollY();
        mainPage.setVisibility(show ? View.GONE : View.VISIBLE);
        incidentsPage.setVisibility(show ? View.VISIBLE : View.GONE);
        if (scroller != null) {
            final int y = show ? 0 : mainScrollY;
            scroller.post(new Runnable() {
                @Override
                public void run() {
                    scroller.scrollTo(0, y);
                }
            });
        }
    }

    /** The scroll view holding a page, or null. */
    private static android.widget.ScrollView scrollerOf(View v) {
        for (android.view.ViewParent p = v == null ? null : v.getParent(); p != null;
                p = p.getParent())
            if (p instanceof android.widget.ScrollView)
                return (android.widget.ScrollView) p;
        return null;
    }

    private void rememberIncidents(boolean on) {
        final android.content.SharedPreferences prefs = prefs();
        if (prefs != null)
            prefs.edit().putBoolean(PREF_INCIDENTS_ON, on).apply();
    }

    @Override
    public void onFeedChanged(IncidentFeed feed) {
        render();
    }

    /** The 511 status line, from what the feed actually has, never from what was asked. */
    private CharSequence incidentsLine() {
        if (incidents == null || !incidents.isOn())
            return "";
        if (incidents.isGatedOut())
            return pluginContext.getString(R.string.incidents_gated_fmt,
                    IncidentSettings.gateLabel(incidents.gateMeters()));
        if (!incidents.everReached())
            return incidents.isStale() || incidents.lastFailedAt() > 0
                    ? pluginContext.getString(R.string.incidents_unreachable)
                    : pluginContext.getString(R.string.incidents_loading);
        switch (incidents.coverage()) {
            case NOT_COVERED:
                return incidents.pickedStates().isEmpty()
                        ? pluginContext.getString(R.string.incidents_no_states)
                        : pluginContext.getString(R.string.incidents_not_covered);
            case NOT_PICKED:
                return pluginContext.getString(R.string.incidents_not_picked_fmt,
                        incidents.suggestedStateName());
            case ZOOM_IN:
                return pluginContext.getString(R.string.incidents_zoom_in);
            case GATED:
                return pluginContext.getString(R.string.incidents_gated_fmt,
                        IncidentSettings.gateLabel(incidents.gateMeters()));
            case WAITING:
                return pluginContext.getString(R.string.incidents_loading);
            default:
                break;
        }
        final String checked = shortClock.format(new Date(incidents.lastReachedAt()));
        if (incidents.isStale()) {
            final SpannableStringBuilder sb = new SpannableStringBuilder(
                    pluginContext.getString(R.string.incidents_stale_fmt, checked));
            sb.setSpan(new ForegroundColorSpan(
                            pluginContext.getResources().getColor(R.color.state_off)),
                    0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }
        // The area is said on the main screen too: a radius around the operator that
        // hides most of the map is a choice they should not have to remember making.
        final String where = incidents.isRadius()
                ? IncidentSettings.scopeText(incidents) : incidents.shownNames();
        final String line = pluginContext.getString(R.string.incidents_checked_fmt, where,
                checked);
        // Over a state they do not follow while one they do is still near: say so
        // under the line, and the Add button below offers it.
        return offersState() ? line + "\n" + pluginContext.getString(
                R.string.incidents_not_picked_fmt, incidents.suggestedStateName()) : line;
    }

    /** The pane offers to add the state under the map: shown or not-picked, never gated. */
    private boolean offersState() {
        if (incidents == null || !incidents.isOn() || incidents.isGatedOut()
                || !incidents.everReached() || incidents.suggestedState() == null)
            return false;
        final IncidentFeed.Coverage c = incidents.coverage();
        return c == IncidentFeed.Coverage.NOT_PICKED || c == IncidentFeed.Coverage.SHOWN;
    }

    private static android.content.SharedPreferences prefs() {
        final MapView mv = MapView.getMapView();
        return mv == null ? null
                : android.preference.PreferenceManager
                        .getDefaultSharedPreferences(mv.getContext());
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
                        remember(wasOn);
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
    /**
     * ON in green, OFF in plain white. Both buttons read the same way, so the
     * pane can be understood without reading either label carefully.
     */
    private void setState(Button b, boolean on) {
        if (b == null)
            return;
        b.setText(on ? R.string.state_on_label : R.string.state_off_label);
        b.setTextColor(pluginContext.getResources().getColor(
                on ? R.color.state_on : R.color.white));
    }

    private void render() {
        if (sourceButton == null)
            return;

        sourceButton.setText(chosen != null ? chosen.label
                : pluginContext.getString(R.string.source_none));

        final boolean on = overlay != null && overlay.isOn();
        setState(toggleButton, on);

        final boolean incidentsOn = incidents != null && incidents.isOn();
        setState(incidentsButton, incidentsOn);
        if (incidentsStatus != null)
            incidentsStatus.setText(incidentsLine());
        if (incidentsAddState != null) {
            final boolean offer = offersState();
            incidentsAddState.setVisibility(offer ? View.VISIBLE : View.GONE);
            if (offer)
                incidentsAddState.setText(pluginContext.getString(
                        R.string.incidents_add_state_fmt, incidents.suggestedStateName()));
        }

        if (persistButton != null) {
            final android.content.SharedPreferences pr = prefs();
            setState(persistButton, pr != null
                    && pr.getBoolean(PREF_PERSIST, false));
        }
        refreshButton.setEnabled(on || incidentsOn);
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
