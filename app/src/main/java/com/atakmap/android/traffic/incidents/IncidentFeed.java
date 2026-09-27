package com.atakmap.android.traffic.incidents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.AtakMapView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The 511 layer's engine: which states are in view, what our server has for them,
 * and keeping the map current while it sits still.
 *
 * <p>It lasts for the plugin's life and is never a Tool: ATAK ends the active tool
 * when another starts, and a feed that stopped because the operator switched base
 * maps would be the FOBS 0.4 mistake again.
 *
 * <p>The phone only ever talks to our host. {@code index.json} names each state we
 * publish and the box it covers, so the plugin carries no state table and a new
 * state needs no release: the view is tested against those boxes, and each state in
 * view is one small file, fetched with If-Modified-Since so a quiet minute costs a
 * 304 and no body.
 */
public class IncidentFeed {

    private static final String TAG = "Traffic511";

    public static final String PREF_SERVER = "traffic.511.server";
    /** Kinds switched off in the pane, comma-separated. */
    private static final String PREF_HIDDEN = "traffic.511.hiddenKinds";
    /** Every type starts on. */
    private static final String DEFAULT_HIDDEN = "";
    /** The zoom gate, as the scale-bar distance in meters; absent or -1 is Always. */
    private static final String PREF_GATE = "traffic.511.gateBarM";
    /**
     * 15 mi on the scale bar: close enough that a metro area's incidents can be told
     * apart, far enough to see a whole corridor. Statewide, CHP alone is a hundred pins.
     */
    private static final double DEFAULT_GATE_M = 15 * 1609.344;
    /**
     * "all" (everything we publish), "view" (what is in view) or "radius"; with a
     * radius, from "me" or "center". The same three IPAWS and Feature Layer offer.
     */
    /**
     * The states the operator follows, comma-separated; absent until the first poll
     * picks the state they are in. Only these are fetched and drawn (operator,
     * 2026-09-26: "should we not have it like the other plugins where you pick a state
     * you care about").
     */
    private static final String PREF_STATES = "traffic.511.states";
    private static final String PREF_SCOPE = "traffic.511.scope";
    private static final String PREF_SCOPE_FROM = "traffic.511.scopeFrom";
    private static final String PREF_SCOPE_RADIUS = "traffic.511.scopeRadiusM";
    /** A radius re-centers once its point has moved a fifth of it, never under this. */
    private static final double SCOPE_MIN_MOVE_M = 250d;
    /** The publisher on the Oracle VM behind cams.takwerx.org (tools/traffic511/deploy). */
    public static final String DEFAULT_SERVER = "https://cams.takwerx.org/traffic511/";
    /** A debug build talks to the poller on the dev Mac, through `adb reverse`. */
    public static final String DEV_SERVER = "http://127.0.0.1:8511/";

    /**
     * 15 s, like the server's CHP poll (operator, 2026-09-26: "lets have both check
     * every 15 seconds"). A quiet check is a 304 with no body; only a changed state
     * file is downloaded.
     */
    private static final long POLL_MS = 15_000L;
    /** No answer from the server for this long and the status line says so. */
    public static final long STALE_MS = 3 * 60_000L;
    /**
     * More states than this in view and nothing is fetched: a whole-country view would
     * pull every state's file every minute to draw dots too small to tap.
     */
    private static final int MAX_STATES = 6;
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");

    /** Why the map shows what it shows, for the status line. */
    public enum Coverage { WAITING, SHOWN, NOT_COVERED, NOT_PICKED, ZOOM_IN, GATED }

    public interface Listener {
        void onFeedChanged(IncidentFeed feed);
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final IncidentOverlay overlay;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "traffic-511");
                    t.setDaemon(true);
                    return t;
                }
            });
    private final AtomicBoolean pollQueued = new AtomicBoolean();

    private final java.util.List<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean on;
    private volatile boolean stopped;
    /** {south, west, north, east}; null means "everything" (the globe, or no view yet). */
    private volatile double[] view;
    private BroadcastReceiver screenWatch;

    // Worker-owned.
    private final Map<String, StateFile> files = new HashMap<>();
    private List<StateInfo> index = Collections.emptyList();
    private String indexModified;
    private Set<String> drawnStates = Collections.emptySet();
    private boolean drawnAny;

    // Read by the pane.
    private volatile Coverage coverage = Coverage.WAITING;
    private volatile long lastReachedAt;
    private volatile long lastFailedAt;
    private volatile String shownNames = "";
    /** Past the zoom gate: nothing drawn and nothing fetched until the map comes closer. */
    private volatile boolean gatedOut;
    private volatile Set<String> hidden;
    /** The circle's center, {lat, lon}, while the scope is a radius; else null. */
    private volatile double[] scopeRef;
    private volatile double scopeRadiusM;
    /** "Around me" was asked for and there is no fix: measuring from the map center. */
    private volatile boolean scopeNoFix;
    /** Main thread: the scope mode last applied, so a switch between modes redraws. */
    private String lastScope = "";
    /** Everything: every state we publish, wherever the map is. */
    private volatile boolean everything;
    /** Where the operator is, {lat, lon}, read on main for the first-run state pick. */
    private volatile double[] selfPos;
    /** A published state in view that is not one of theirs, for "Add Nebraska". */
    private volatile String suggestCode;
    private volatile String suggestName;
    /** State outlines for naming the state under the map; loaded on first use. */
    private StateOutlines outlines;
    /** Every state we publish, code to name, in the index's order. */
    private volatile Map<String, String> published = Collections.emptyMap();
    /** What is on the map now, by the incident's own id. Replaced whole, never edited. */
    private volatile Map<String, Incident> current = Collections.emptyMap();

    private static final class StateInfo {
        final String code;
        final String name;
        final String file;
        final double s, w, n, e;

        StateInfo(String code, String name, String file, double w, double s, double e,
                double n) {
            this.code = code;
            this.name = name;
            this.file = file;
            this.w = w;
            this.s = s;
            this.e = e;
            this.n = n;
        }

        boolean meets(double[] v) {
            return v == null || !(v[2] < s || v[0] > n || v[3] < w || v[1] > e);
        }
    }

    private static final class StateFile {
        String modified;
        List<Incident> incidents = Collections.emptyList();
    }

    public IncidentFeed(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.overlay = new IncidentOverlay(mapView, pluginContext);
        this.hidden = readHidden();
        this.scopeRadiusM = scopeRadiusMeters();
    }

    // ------------------------------------------------------------------ settings

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
    }

    private Set<String> readHidden() {
        final Set<String> out = new java.util.HashSet<>();
        final String s = prefs().getString(PREF_HIDDEN, DEFAULT_HIDDEN);
        for (String k : (s == null ? "" : s).split(","))
            if (!k.trim().isEmpty())
                out.add(k.trim());
        return java.util.Collections.unmodifiableSet(out);
    }

    /**
     * Whether a kind is drawn. The one rule for what is shown, read by the map and by
     * the pane's switches alike, so they cannot disagree.
     */
    public boolean isKindShown(String kind) {
        return !hidden.contains(kind);
    }

    /** A type switch in the pane. Redraws from what is already fetched; no request. */
    public void setKindShown(String kind, boolean shown) {
        final Set<String> next = new java.util.HashSet<>(hidden);
        if (shown)
            next.remove(kind);
        else
            next.add(kind);
        hidden = java.util.Collections.unmodifiableSet(next);
        prefs().edit().putString(PREF_HIDDEN, android.text.TextUtils.join(",", next)).apply();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (on && drawnAny)
                    redraw(drawnStates, true);
            }
        });
        changed();
    }

    /** "all", "view" or "radius". */
    public String scope() {
        final String s = prefs().getString(PREF_SCOPE, "view");
        return "all".equals(s) || "radius".equals(s) ? s : "view";
    }

    // ------------------------------------------------------------------ states

    /** The states the operator follows; empty until the first poll picks one. */
    public Set<String> pickedStates() {
        final String s = prefs().getString(PREF_STATES, null);
        final Set<String> out = new LinkedHashSet<>();
        if (s != null)
            for (String c : s.split(","))
                if (STATE.matcher(c.trim()).matches())
                    out.add(c.trim());
        return out;
    }

    /** Main thread. */
    public void setPickedStates(Set<String> codes) {
        prefs().edit().putString(PREF_STATES, android.text.TextUtils.join(",", codes)).apply();
        suggestCode = null;
        requestPoll();
        changed();
    }

    /** Main thread: the one-tap "Add Nebraska". */
    public void addState(String code) {
        final Set<String> next = pickedStates();
        next.add(code);
        setPickedStates(next);
    }

    /** Every state we publish, code to name; empty before the first answer. */
    public Map<String, String> publishedStates() {
        return published;
    }

    /** The name we publish a state under, or the code itself. */
    public String stateName(String code) {
        final String n = published.get(code);
        return n != null ? n : code;
    }

    /** A published state in view the operator does not follow, or null. */
    public String suggestedState() {
        return suggestCode;
    }

    public String suggestedStateName() {
        return suggestName;
    }

    /** True when the scope is a radius. */
    public boolean isRadius() {
        return "radius".equals(scope());
    }

    /** True when the scope is everything we publish, wherever the map is. */
    public boolean isEverything() {
        return "all".equals(scope());
    }

    /** "me" or "center". */
    public String scopeFrom() {
        return prefs().getString(PREF_SCOPE_FROM, "me");
    }

    public double scopeRadiusMeters() {
        return prefs().getFloat(PREF_SCOPE_RADIUS, (float) (25 * 1609.344));
    }

    public boolean scopeHasNoFix() {
        return isRadius() && scopeNoFix;
    }

    /**
     * Main thread. Everything, what is in view, or a circle of {@code radiusM} around
     * the operator or the map center. Redraws from what is already fetched, and fetches
     * only if the new scope reaches a state not yet on hand.
     */
    public void setScope(String mode, double radiusM, String from) {
        final String m = "all".equals(mode) || ("radius".equals(mode) && radiusM > 0)
                ? mode : "view";
        prefs().edit()
                .putString(PREF_SCOPE, m)
                .putString(PREF_SCOPE_FROM, "center".equals(from) ? "center" : "me")
                .putFloat(PREF_SCOPE_RADIUS, radiusM > 0 ? (float) radiusM
                        : (float) scopeRadiusMeters())
                .apply();
        followScope(true);
    }

    /**
     * Main thread. Keeps the circle where it says it is: on the operator as they move,
     * or on the map center as it pans. Feature Layer's rule, which IPAWS copies: move it
     * once its point has gone a fifth of the radius, never for less than 250 m.
     */
    private void followScope(boolean force) {
        if (!on)
            return;
        if (!isRadius()) {
            final boolean was = scopeRef != null || !scope().equals(lastScope);
            lastScope = scope();
            scopeRef = null;
            scopeNoFix = false;
            if (was || force)
                rescope();
            return;
        }
        final double r = scopeRadiusMeters();
        final boolean fromCenter = "center".equals(scopeFrom());
        double[] now = fromCenter ? null : ownPosition();
        final boolean noFix = now == null && !fromCenter;
        if (now == null)
            now = mapCenter();
        if (now == null)
            return;
        final double[] was = scopeRef;
        if (!force && was != null && r == scopeRadiusM && noFix == scopeNoFix
                && distanceM(was[0], was[1], now[0], now[1])
                        < Math.max(SCOPE_MIN_MOVE_M, r * 0.2))
            return;
        scopeRef = now;
        scopeRadiusM = r;
        scopeNoFix = noFix;
        rescope();
    }

    /** The scope changed: redraw from what is on hand, and fetch if new states are needed. */
    private void rescope() {
        everything = isEverything();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (on && drawnAny)
                    redraw(drawnStates, true);
            }
        });
        requestPoll();
        changed();
    }

    /**
     * A usable own position, {lat, lon}, or null. The self marker reads 0,0 before a fix
     * and calls itself valid, which put Feature Layer's "near me" in the Gulf of Guinea.
     */
    private double[] ownPosition() {
        final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
        final com.atakmap.coremap.maps.coords.GeoPoint p = self == null ? null : self.getPoint();
        if (p == null || !p.isValid()
                || (Math.abs(p.getLatitude()) < 0.01 && Math.abs(p.getLongitude()) < 0.01))
            return null;
        return new double[] { p.getLatitude(), p.getLongitude() };
    }

    private double[] mapCenter() {
        final com.atakmap.coremap.maps.coords.GeoPoint c = mapView.getPoint().get();
        if (c == null || !c.isValid())
            return null;
        return new double[] { c.getLatitude(), c.getLongitude() };
    }

    /** Equirectangular distance; good to well under 1% at the radii offered. */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        final double k = Math.PI / 180d;
        final double x = (lon2 - lon1) * k * Math.cos((lat1 + lat2) / 2d * k);
        final double y = (lat2 - lat1) * k;
        return Math.sqrt(x * x + y * y) * 6371008.8d;
    }

    /** Whether an incident is inside the scope. The one rule, as isKindShown is. */
    private boolean inScope(Incident in) {
        final double[] ref = scopeRef;
        if (ref == null)
            return true;
        final double r = scopeRadiusM;
        for (double[] part : in.parts)
            for (int i = 0; i + 1 < part.length; i += 2)
                if (distanceM(ref[0], ref[1], part[i + 1], part[i]) <= r)
                    return true;
        return false;
    }

    /** The scale-bar distance past which nothing is drawn; MAX_VALUE is Always. */
    public double gateMeters() {
        final float g = prefs().getFloat(PREF_GATE, (float) DEFAULT_GATE_M);
        return g > 0f ? g : Double.MAX_VALUE;
    }

    /** Main thread. */
    public void setGate(double barMeters) {
        prefs().edit().putFloat(PREF_GATE,
                barMeters == Double.MAX_VALUE ? -1f : (float) barMeters).apply();
        applyGate(true);
    }

    public boolean isGatedOut() {
        return on && gatedOut;
    }

    /**
     * Main thread. What the scale bar reads now against the gate, both in meters, the
     * way IPAWS compares them: a resolution threshold through a nominal bar width drew
     * "30.06 mi or closer" at a 64 mi bar (s10-dev-1, 2026-09-25). Past the gate the
     * layer is hidden and polls stop; coming back in, it shows and polls at once.
     */
    private void applyGate(boolean force) {
        if (!on)
            return;
        final double gate = gateMeters();
        // A 2% margin: "Use this zoom" stores the bar's reading as a float, which can
        // land a hair under the reading it came from, and then the zoom the operator
        // chose was itself outside the gate ("Shown at 89.4 mi or closer" with the bar
        // at 89.36 mi and nothing drawn, s10-dev-2, 2026-09-26).
        final boolean past = gate != Double.MAX_VALUE
                && ScaleBar.meters(mapView) > gate * 1.02;
        if (!force && past == gatedOut)
            return;
        gatedOut = past;
        overlay.setShown(!past);
        if (past)
            coverage = Coverage.GATED;
        else {
            if (coverage == Coverage.GATED)
                coverage = drawnAny ? Coverage.SHOWN : Coverage.WAITING;
            requestPoll();
        }
        changed();
    }

    /** The pane and an open details page both follow the feed. */
    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l))
            listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isOn() {
        return on;
    }

    /**
     * The incident with this id as it is on the map now, or null once it has cleared.
     * Details look incidents up here rather than by feature id: the store is rewritten
     * every minute and every rewrite gives every feature a new id, so a tap followed by
     * Details a few seconds later read "cleared" for an incident still on the map
     * (s10-dev-2, 2026-09-26).
     */
    public Incident find(String id) {
        return id == null ? null : current.get(id);
    }

    public IncidentOverlay overlay() {
        return overlay;
    }

    public Coverage coverage() {
        return coverage;
    }

    public long lastReachedAt() {
        return lastReachedAt;
    }

    /** True when the last attempt failed and the server has been out of reach a while. */
    public boolean isStale() {
        return on && lastFailedAt > lastReachedAt
                && System.currentTimeMillis() - lastReachedAt > STALE_MS;
    }

    public long lastFailedAt() {
        return lastFailedAt;
    }

    public boolean everReached() {
        return lastReachedAt > 0;
    }

    /** "California", or "California, Nevada": the states on the map now. */
    public String shownNames() {
        return shownNames;
    }

    // ------------------------------------------------------------------ on / off

    /** Main thread. Returns a failure phrased for the operator, or null. */
    public String turnOn() {
        if (stopped)
            return "The plugin is stopping.";
        try {
            overlay.attach();
        } catch (Exception e) {
            Log.w(TAG, "attach failed", e);
            return "Could not open the 511 layer: " + e.getMessage();
        }
        on = true;
        coverage = Coverage.WAITING;
        gatedOut = false;
        overlay.setShown(true);
        mapView.addOnMapMovedListener(moved);
        watchScreen(true);
        readView();
        main.removeCallbacks(tick);
        main.postDelayed(tick, POLL_MS);
        followScope(true);
        applyGate(true);
        changed();
        return null;
    }

    /** Main thread. Takes the dots off at once and frees them on the worker. */
    public void turnOff() {
        if (!on)
            return;
        on = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(viewSettled);
        mapView.removeOnMapMovedListener(moved);
        watchScreen(false);
        overlay.setShown(false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                overlay.rewrite(Collections.<Incident>emptyList());
                drawnStates = Collections.emptySet();
                drawnAny = false;
                shownNames = "";
                current = Collections.emptyMap();
            }
        });
        changed();
    }

    /** Plugin stop, main thread. */
    public void stop() {
        turnOff();
        stopped = true;
        overlay.detach();
        worker.shutdown();
    }

    /** The operator asked for fresh data now (the pane's Refresh). */
    public void refreshNow() {
        requestPoll();
    }

    // ------------------------------------------------------------------ timing

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!on)
                return;
            followScope(false);   // My Location moves without the map moving
            requestPoll();
            main.postDelayed(this, POLL_MS);
        }
    };

    /**
     * onMapMoved runs on the GL thread, every frame of a pinch: it only posts, and the
     * posts coalesce, so the view is read once the map has settled.
     */
    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            main.removeCallbacks(viewSettled);
            main.postDelayed(viewSettled, 400);
        }
    };

    private final Runnable viewSettled = new Runnable() {
        @Override
        public void run() {
            if (!on)
                return;
            applyGate(false);
            followScope(false);
            final Set<String> before = wanted(index, scopeBox());
            readView();
            // A pan inside one state changes nothing worth a request; a pan into a
            // new one fetches it now rather than at the next minute.
            if (!before.equals(wanted(index, scopeBox())))
                requestPoll();
        }
    };

    private void readView() {
        selfPos = ownPosition();
        final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
        if (b == null) {
            view = null;
            return;
        }
        final double s = b.getSouth(), w = b.getWest(), n = b.getNorth(), e = b.getEast();
        // On the globe getBounds() can be NaN; "in view" then means everything.
        if (Double.isNaN(s) || Double.isNaN(w) || Double.isNaN(n) || Double.isNaN(e)
                || n <= s || e <= w || b.crossesIDL()) {
            view = null;
            return;
        }
        view = new double[] { s, w, n, e };
    }

    /**
     * Nothing can draw with the screen off, so polling then spends battery and data on
     * a map nobody sees. It holds, and polls the moment the screen comes back: that is
     * the first moment the answer can matter. Same rule as the tiles.
     */
    private void watchScreen(boolean watch) {
        final Context ctx = mapView.getContext();
        if (watch && screenWatch == null) {
            screenWatch = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (Intent.ACTION_SCREEN_ON.equals(i.getAction()))
                        requestPoll();
                }
            };
            ctx.registerReceiver(screenWatch, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } else if (!watch && screenWatch != null) {
            try {
                ctx.unregisterReceiver(screenWatch);
            } catch (IllegalArgumentException ignored) {
                // Already gone with the context.
            }
            screenWatch = null;
        }
    }

    private boolean screenOn() {
        final PowerManager pm = (PowerManager) mapView.getContext()
                .getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void requestPoll() {
        if (!on || stopped || !screenOn())
            return;
        if (!pollQueued.compareAndSet(false, true))
            return; // one already waiting will see the newest view
        worker.execute(new Runnable() {
            @Override
            public void run() {
                pollQueued.set(false);
                try {
                    poll();
                } catch (RuntimeException e) {
                    // Never let a plugin thread take ATAK down.
                    Log.e(TAG, "poll failed hard", e);
                }
                changed();
            }
        });
    }

    // ------------------------------------------------------------------ the poll

    /** Worker thread. */
    private void poll() {
        if (!on)
            return;
        if (gatedOut) {
            coverage = Coverage.GATED;
            return; // nothing drawn, so nothing worth a request
        }
        final String base = server();
        if (base == null) {
            lastFailedAt = System.currentTimeMillis();
            Log.w(TAG, "511 server setting is not a usable address");
            return;
        }
        try {
            final Response r = get(base + "index.json", indexModified);
            if (r.body != null) {
                index = parseIndex(r.body);
                indexModified = r.modified;
            }
        } catch (IOException e) {
            lastFailedAt = System.currentTimeMillis();
            Log.w(TAG, "index: " + e.getMessage());
            return; // keep whatever is drawn; the status line ages it
        }
        lastReachedAt = System.currentTimeMillis();
        final Map<String, String> names = new java.util.LinkedHashMap<>();
        for (StateInfo st : index)
            names.put(st.code, st.name);
        published = java.util.Collections.unmodifiableMap(names);

        Set<String> picked = pickedStates();
        if (prefs().getString(PREF_STATES, null) == null) {
            // First run: the state they are in, by GPS, else by where the map is.
            final String home = stateAt(selfPos != null ? selfPos : centerOf(view));
            picked = new LinkedHashSet<>();
            if (home != null)
                picked.add(home);
            prefs().edit().putString(PREF_STATES,
                    android.text.TextUtils.join(",", picked)).apply();
            Log.d(TAG, "first run: following " + picked);
        }

        final double[] v = scopeBox();
        final Set<String> inView = wanted(index, v);
        final Set<String> want = new LinkedHashSet<>(inView);
        want.retainAll(picked);
        // Say so when the map is over a state we publish that they do not follow,
        // naming the one under the middle of the map when there is one.
        final Set<String> notPicked = new LinkedHashSet<>(inView);
        notPicked.removeAll(picked);
        // The state under the middle of the map is offered whenever it is not one of
        // theirs, even while a picked state's padded box still meets the view: over
        // Las Vegas, California's box did, and Nevada was never offered (S22,
        // 2026-09-26). With nothing picked in view, any state in view will do.
        String suggest = null;
        if (!everything) {
            final String mid = stateAt(scopeRef != null ? scopeRef : centerOf(view));
            if (mid != null && !picked.contains(mid))
                suggest = mid;
            else if (want.isEmpty() && !notPicked.isEmpty())
                suggest = notPicked.iterator().next();
        }
        suggestCode = suggest;
        suggestName = suggest == null ? null : stateName(suggest);
        // Everything is everything, asked for by name; the cap is for a view that
        // happens to take in half the country, which nobody asked to download.
        if (!everything && want.size() > MAX_STATES) {
            coverage = Coverage.ZOOM_IN;
            redraw(Collections.<String>emptySet(), false);
            return;
        }
        if (want.isEmpty()) {
            coverage = suggest != null ? Coverage.NOT_PICKED : Coverage.NOT_COVERED;
            redraw(want, false);
            return;
        }

        boolean changed = !want.equals(drawnStates);
        for (StateInfo st : index) {
            if (!want.contains(st.code))
                continue;
            StateFile f = files.get(st.code);
            if (f == null) {
                f = new StateFile();
                files.put(st.code, f);
            }
            try {
                final Response r = get(base + st.file, f.modified);
                if (r.body != null) {
                    f.incidents = parseState(r.body);
                    f.modified = r.modified;
                    changed = true;
                }
            } catch (IOException e) {
                lastFailedAt = System.currentTimeMillis();
                Log.w(TAG, st.code + ": " + e.getMessage());
                // The state keeps what it last had.
            }
        }
        coverage = gatedOut ? Coverage.GATED : Coverage.SHOWN;
        if (changed || !drawnAny)
            redraw(want, true);
    }

    /** Draws these states' incidents; unforced, only when the set of states changed. */
    private void redraw(Set<String> states, boolean force) {
        if (!force && drawnAny && states.equals(drawnStates))
            return;
        final List<Incident> all = new ArrayList<>();
        final StringBuilder names = new StringBuilder();
        for (StateInfo st : index) {
            if (!states.contains(st.code))
                continue;
            final StateFile f = files.get(st.code);
            if (f != null)
                for (Incident in : f.incidents)
                    if (isKindShown(in.kind) && inScope(in))
                        all.add(in);
            if (names.length() > 0)
                names.append(", ");
            names.append(st.name);
        }
        if (!on)
            return; // switched off while this poll was running
        final Map<String, Incident> byId = new HashMap<>();
        for (Incident in : all)
            byId.put(in.id, in);
        // Before the rewrite: a tap on a new feature must find its incident.
        current = byId;
        overlay.rewrite(all);
        drawnStates = new LinkedHashSet<>(states);
        drawnAny = true;
        shownNames = names.toString();
        Log.d(TAG, "drew " + overlay.count() + " incidents for " + drawnStates);
    }

    /**
     * The box states are fetched for, {south, west, north, east}: the circle's when the
     * scope is a radius (the operator may have panned away from it), else the view.
     */
    private double[] scopeBox() {
        if (everything)
            return null; // meets every state
        final double[] ref = scopeRef;
        if (ref == null)
            return view;
        final double r = scopeRadiusM;
        final double dLat = r / 111_320d;
        final double dLon = r / (111_320d * Math.max(0.05, Math.cos(Math.toRadians(ref[0]))));
        return new double[] { ref[0] - dLat, ref[1] - dLon, ref[0] + dLat, ref[1] + dLon };
    }

    /** {lat, lon} of a view box's middle, or null. */
    private static double[] centerOf(double[] v) {
        return v == null ? null : new double[] { (v[0] + v[2]) / 2d, (v[1] + v[3]) / 2d };
    }

    /**
     * The published state this point is in: by the state outlines shipped in the
     * plugin, else by the published boxes, where the smallest box wins because the
     * boxes are padded and overlap along every border. Worker thread (it reads an
     * asset the first time).
     */
    private String stateAt(double[] p) {
        if (p == null)
            return null;
        if (outlines == null)
            outlines = StateOutlines.load(pluginContext);
        if (!outlines.isEmpty()) {
            final List<String> codes = new ArrayList<>(index.size());
            for (StateInfo st : index)
                codes.add(st.code);
            final String code = outlines.stateAt(p[0], p[1], codes);
            if (code != null)
                return code;
        }
        StateInfo best = null;
        double bestArea = Double.MAX_VALUE;
        for (StateInfo st : index) {
            if (p[0] < st.s || p[0] > st.n || p[1] < st.w || p[1] > st.e)
                continue;
            final double area = (st.n - st.s) * (st.e - st.w);
            if (area < bestArea) {
                best = st;
                bestArea = area;
            }
        }
        return best == null ? null : best.code;
    }

    private static Set<String> wanted(List<StateInfo> index, double[] v) {
        final Set<String> out = new LinkedHashSet<>();
        for (StateInfo st : index)
            if (st.meets(v))
                out.add(st.code);
        return out;
    }

    private static List<StateInfo> parseIndex(byte[] body) throws IOException {
        try {
            final JSONObject states = new JSONObject(new String(body, StandardCharsets.UTF_8))
                    .optJSONObject("states");
            final List<StateInfo> out = new ArrayList<>();
            if (states == null)
                return out;
            final java.util.Iterator<String> it = states.keys();
            while (it.hasNext()) {
                final String code = it.next();
                final JSONObject o = states.optJSONObject(code);
                final JSONArray bb = o == null ? null : o.optJSONArray("bbox");
                // The file name is ours to build, never the server's to choose: one
                // state code, one fixed suffix.
                if (!STATE.matcher(code).matches() || bb == null || bb.length() != 4)
                    continue;
                out.add(new StateInfo(code, o.optString("name", code),
                        code + ".geojson", bb.optDouble(0), bb.optDouble(1),
                        bb.optDouble(2), bb.optDouble(3)));
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("index is not JSON");
        }
    }

    private static List<Incident> parseState(byte[] body) throws IOException {
        try {
            final JSONArray fs = new JSONObject(new String(body, StandardCharsets.UTF_8))
                    .optJSONArray("features");
            final List<Incident> out = new ArrayList<>();
            if (fs == null)
                return out;
            for (int i = 0; i < fs.length(); i++) {
                final Incident in = Incident.parse(fs.optJSONObject(i));
                if (in != null)
                    out.add(in);
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("state file is not JSON");
        }
    }

    // ------------------------------------------------------------------ http

    private static final class Response {
        final byte[] body; // null on 304
        final String modified;

        Response(byte[] body, String modified) {
            this.body = body;
            this.modified = modified;
        }
    }

    /**
     * The server setting, or the debug/release default; always ending in "/". Https
     * only, except a loopback address, which is the dev Mac through `adb reverse`.
     */
    private String server() {
        String s = null;
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(
                mapView.getContext());
        if (p != null)
            s = p.getString(PREF_SERVER, null);
        if (s == null || s.trim().isEmpty())
            s = com.atakmap.android.traffic.plugin.BuildConfig.DEBUG ? DEV_SERVER
                    : DEFAULT_SERVER;
        s = s.trim();
        if (!s.endsWith("/"))
            s = s + "/";
        try {
            final URL u = new URL(s);
            final boolean loopback = "127.0.0.1".equals(u.getHost())
                    || "localhost".equals(u.getHost());
            if ("https".equals(u.getProtocol()) || ("http".equals(u.getProtocol()) && loopback))
                return s;
        } catch (java.net.MalformedURLException ignored) {
            // Falls through to "not usable".
        }
        return null;
    }

    private static Response get(String url, String ifModifiedSince) throws IOException {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            // Following a redirect would carry the request somewhere server() never
            // checked. Our host has no reason to redirect.
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent",
                    "takwerx-traffic-atak-plugin (https://github.com/takwerx/traffic)");
            if (ifModifiedSince != null)
                c.setRequestProperty("If-Modified-Since", ifModifiedSince);
            final int status = c.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED)
                return new Response(null, ifModifiedSince);
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("HTTP " + status);
            in = c.getInputStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            final byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES)
                    throw new IOException("response too large");
                out.write(buf, 0, n);
            }
            return new Response(out.toByteArray(), c.getHeaderField("Last-Modified"));
        } finally {
            if (in != null)
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already have the body or the failure.
                }
            if (c != null)
                c.disconnect();
        }
    }

    private void changed() {
        if (listeners.isEmpty())
            return;
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners)
                    l.onFeedChanged(IncidentFeed.this);
            }
        });
    }
}
