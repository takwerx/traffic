package com.atakmap.android.traffic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.io.IOProviderFactory;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.raster.DatasetDescriptor;
import com.atakmap.map.layer.raster.DatasetDescriptorFactory2;
import com.atakmap.map.layer.raster.DatasetRasterLayer2;
import com.atakmap.map.layer.raster.RuntimeRasterDataStore;
import com.atakmap.map.layer.raster.service.OnlineImageryExtension;

import java.io.File;
import java.util.Set;

/**
 * One live tile source, drawn over whatever base map the operator is already using,
 * kept fresh while the map sits still.
 *
 * <h3>Why this class exists at all</h3>
 *
 * ATAK can already refresh an online tile source. {@code <tileUpdate>} in a MOBAC XML
 * parses through to {@code MobacTileReader.start()}, which expires the cached tiles and
 * bumps the tile version so the renderer re-reads them. Two things stop that from being
 * useful:
 *
 * <ul>
 *   <li>{@code start()} is called from {@code GLQuadTileNode4.draw}, and the map surface
 *       renders on demand ({@code GLMapSurface} sets {@code RENDERMODE_WHEN_DIRTY}).
 *       A device that pans keeps drawing and stays live. A tablet on a desk draws
 *       nothing, so nothing ever expires. That is the whole reported defect.</li>
 *   <li>A {@code customMultiLayerMapSource} — the one arrangement that puts traffic over
 *       a base map — is constructed with no refresh interval at all, so even a moving
 *       device cannot refresh a composited source.</li>
 * </ul>
 *
 * <h3>What this class does instead</h3>
 *
 * It owns its own layer rather than reaching into ATAK's. That answers both halves:
 *
 * <ul>
 *   <li>The layer goes in {@link MapView.RenderStack#RASTER_OVERLAYS}, which sits above
 *       {@code MAP_LAYERS} where ATAK's base map card lives. No multi-layer XML is
 *       involved, so the missing-interval gap never applies. It also sidesteps the card:
 *       ATAK's Native and Mobile imagery are mutually exclusive tabs of one
 *       {@code CardLayer}, which is the real reason "traffic over my own base map" could
 *       not be arranged from the layer manager.</li>
 *   <li>Owning the layer means owning its {@link OnlineImageryExtension}, so the refresh
 *       interval can be set on the live layer at runtime, and a heartbeat can force the
 *       draw pump that makes {@code start()} run. ATAK then does the expiry, the version
 *       bump, the refetch and the repaint by itself.</li>
 * </ul>
 *
 * The heartbeat is the entire trick. This class never touches {@code TileCacheControl}
 * and never reflects into anything.
 */
public class LiveOverlay {

    private static final String TAG = "TrafficOverlay";

    /** Nothing useful refreshes faster than this, and the GPU wake is not free. */
    public static final long MIN_INTERVAL_MS = 15000L;

    /** Told when the overlay turns on or off, or when a heartbeat fires. */
    public interface Listener {
        void onOverlayChanged(LiveOverlay overlay);
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Source source;
    private RuntimeRasterDataStore store;
    private DatasetRasterLayer2 layer;
    private OnlineImageryExtension refresh;

    private long intervalMs;
    private long lastPumpAt;
    private int pumps;
    private Listener listener;

    /**
     * Whether the display is on.
     *
     * Nothing can refresh while the screen is off: the map surface does not render, so
     * {@code MobacTileReader.start()} never runs however often we ask for a frame. Rather
     * than burn battery pumping into the dark, the heartbeat stops with the screen and
     * the overlay refreshes the instant it comes back — which is the only moment the
     * freshness matters, because that is the first moment anyone can see it.
     */
    private boolean screenOn = true;
    private BroadcastReceiver screenWatch;

    /**
     * The tile cache ATAK writes refetched tiles into, and when it last changed.
     *
     * <p>Asking for a refresh and receiving one are different events, and only the second
     * is worth showing an operator. If the network is down, the heartbeat keeps ticking
     * happily while the tiles on screen get older — which is precisely the failure the
     * status line exists to catch. So freshness is read from the cache file's
     * modification time rather than from our own timer: when a tile actually lands, the
     * file changes.
     */
    private File tileCache;
    private long tileCacheStamp;
    private long lastTileChangeAt;
    private long lastCheckAt;

    /**
     * When the device was last woken, so the status line can say that the tiles on screen
     * arrived <em>because</em> the operator picked the device up. Without this the wake
     * refresh is invisible: all it leaves behind is a recent timestamp, and the operator
     * has to infer why it is recent.
     */
    private long wokeAt;

    /** A source the plugin can turn on: a MOBAC XML shipped in the plugin's assets. */
    public static class Source {
        public final String id;
        public final String label;
        public final String asset;
        /** Fallback only. The XML's own {@code <tileUpdate>} wins when it has one. */
        public final long defaultIntervalMs;

        public Source(String id, String label, String asset, long defaultIntervalMs) {
            this.id = id;
            this.label = label;
            this.asset = asset;
            this.defaultIntervalMs = defaultIntervalMs;
        }
    }

    public LiveOverlay(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isOn() {
        return layer != null;
    }

    public Source getSource() {
        return source;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public long getLastPumpAt() {
        return lastPumpAt;
    }

    public int getPumpCount() {
        return pumps;
    }

    // ------------------------------------------------------------------ on / off

    /**
     * Turn a source on. Any source already showing is taken down first — one live
     * overlay at a time, because a second one would just hide the first.
     *
     * @return null on success, or a sentence saying what failed.
     */
    public String turnOn(Source src) {
        turnOff();
        try {
            final File xml = stageAsset(src);
            if (xml == null)
                return "could not unpack " + src.asset;

            // No working directory, which is not the same as no cache. It stops
            // MobacMapSourceLayerInfoSpi writing a per-plugin "offlineCache" extra, but
            // that Spi also honours the global ConfigOptions "imagery.offline-cache-dir",
            // which ATAK sets — so the reader still gets a caching TileProxy, and the
            // one-week expiry floor in MobacTileClient2 still applies. Verified on the
            // device: the cache lands in atak/imagecache/<source name>.sqlite. Defeating
            // that floor is exactly what the refresh interval is for.
            final Set<DatasetDescriptor> descs =
                    DatasetDescriptorFactory2.create(xml, null, null, null);
            if (descs == null || descs.isEmpty())
                return "ATAK did not recognise " + xml.getName() + " as a map source";

            store = new RuntimeRasterDataStore();
            DatasetDescriptor added = null;
            for (DatasetDescriptor d : descs)
                added = store.add(d);
            if (added == null)
                return "map source held no dataset";

            layer = new DatasetRasterLayer2(src.label, store, 1);
            layer.setVisible(true);

            refresh = layer.getExtension(OnlineImageryExtension.class);
            if (refresh == null) {
                // Not fatal — the layer still draws, it just will not self-refresh.
                Log.w(TAG, "layer has no OnlineImageryExtension; refresh disabled");
            }

            intervalMs = Math.max(MIN_INTERVAL_MS, intervalOf(added, src));
            if (refresh != null)
                refresh.setCacheAutoRefreshInterval(intervalMs);

            // The descriptor knows exactly where its tiles are cached — no guessing at
            // the path from the source name and ATAK's config.
            final String cachePath = added.getExtraData("offlineCache");
            tileCache = cachePath != null ? new File(cachePath) : null;
            tileCacheStamp = stampOf(tileCache);
            lastTileChangeAt = 0L;
            lastCheckAt = 0L;

            mapView.addLayer(MapView.RenderStack.RASTER_OVERLAYS, layer);
            source = src;
            lastPumpAt = 0L;
            pumps = 0;
            watchScreen();
            if (screenOn)
                startHeartbeat();
            Log.d(TAG, "on: " + src.label + " every " + intervalMs + "ms");
            fire();
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "turning on " + src.label + " failed", t);
            turnOff();
            return describe(t);
        }
    }

    /** Take the overlay down and stop the heartbeat. Safe to call when already off. */
    public void turnOff() {
        stopHeartbeat();
        unwatchScreen();
        if (layer != null) {
            try {
                mapView.removeLayer(MapView.RenderStack.RASTER_OVERLAYS, layer);
            } catch (Throwable t) {
                Log.w(TAG, "removing layer failed", t);
            }
            layer = null;
        }
        if (store != null) {
            try {
                store.clear();
                store.dispose();
            } catch (Throwable t) {
                Log.w(TAG, "disposing store failed", t);
            }
            store = null;
        }
        refresh = null;
        source = null;
        tileCache = null;
        tileCacheStamp = 0L;
        lastTileChangeAt = 0L;
        lastCheckAt = 0L;
        wokeAt = 0L;
        fire();
    }

    /**
     * Refresh now, without waiting out the interval.
     *
     * <p>This is harder than it looks, and getting it wrong is why ATAK's own manual
     * refresh appears to do nothing. {@code refreshCache()} only marks the tiles expired.
     * The renderer re-reads a tile when {@code GLQuadTileNode4.needsRefresh()} goes true,
     * and that wants the <em>tile version</em> to change — which happens in exactly one
     * place, inside the interval gate in {@code MobacTileReader.start()}:
     *
     * <pre>if (refreshMonitor.check(control.getCacheAutoRefreshInterval(), uptime)) { … ++version; }</pre>
     *
     * <p>So a pump partway through an interval expires the tiles and then changes
     * nothing: {@code IntervalMonitor.check} returns false until the full interval has
     * elapsed. Measured on the device — an unlock at 06:35:39 produced no fetch until the
     * ordinary interval boundary 90 seconds later.
     *
     * <p>The gate is the interval, and the interval is ours to set. Drop it to 1 ms, pump,
     * and the check passes immediately: expire, version bump, refetch. Then put the real
     * interval straight back, so the layer does not sit in refetch-every-frame mode.
     */
    public void refreshNow() {
        if (refresh == null) {
            pump();
            return;
        }
        try {
            refresh.setCacheAutoRefreshInterval(1L);
            refresh.refreshCache();
        } catch (Throwable t) {
            Log.w(TAG, "refreshCache failed", t);
        }
        pump();
        // One frame is enough to carry the dropped interval down to the tile reader and
        // trip the gate. Restoring promptly bounds the window in which every draw would
        // expire the cache again.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pump();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (refresh == null)
                            return;
                        try {
                            refresh.setCacheAutoRefreshInterval(intervalMs);
                        } catch (Throwable t) {
                            Log.w(TAG, "restoring interval failed", t);
                        }
                    }
                }, 400L);
            }
        }, 250L);
    }

    /** Change the beat. Below {@link #MIN_INTERVAL_MS} is clamped, not rejected. */
    public void setIntervalMs(long ms) {
        intervalMs = Math.max(MIN_INTERVAL_MS, ms);
        if (refresh != null)
            refresh.setCacheAutoRefreshInterval(intervalMs);
        if (isOn()) {
            stopHeartbeat();
            startHeartbeat();
        }
        fire();
    }

    // --------------------------------------------------------------- the heartbeat

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            if (!isOn())
                return;
            pump();
            handler.postDelayed(this, intervalMs);
        }
    };

    private void startHeartbeat() {
        handler.postDelayed(beat, intervalMs);
        handler.postDelayed(watchTiles, TILE_WATCH_MS);
    }

    private void stopHeartbeat() {
        handler.removeCallbacks(beat);
        handler.removeCallbacks(watchTiles);
    }

    // -------------------------------------------------------------- did tiles arrive?

    /** Often enough to feel live, rare enough that a stat call costs nothing. */
    private static final long TILE_WATCH_MS = 4000L;

    private final Runnable watchTiles = new Runnable() {
        @Override
        public void run() {
            if (!isOn())
                return;
            checkTiles();
            handler.postDelayed(this, TILE_WATCH_MS);
        }
    };

    /** Note the moment the cache changed, which is the moment new tiles arrived. */
    private void checkTiles() {
        if (tileCache == null)
            return;
        final long stamp = stampOf(tileCache);
        lastCheckAt = System.currentTimeMillis();
        if (stamp != 0L && stamp != tileCacheStamp) {
            tileCacheStamp = stamp;
            lastTileChangeAt = lastCheckAt;
            fire();
        }
    }

    private static long stampOf(File f) {
        try {
            if (f == null || !IOProviderFactory.exists(f))
                return 0L;
            return IOProviderFactory.lastModified(f);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** When tiles genuinely last changed, or 0 if none have since this was turned on. */
    public long getLastTileChangeAt() {
        return lastTileChangeAt;
    }

    /** When the plugin last looked, whether or not anything had changed. */
    public long getLastCheckAt() {
        return lastCheckAt;
    }

    /**
     * True when the tiles on screen arrived from a wake refresh, and recently enough that
     * saying so still means something. After a couple of minutes it is just the ordinary
     * interval doing its job and the plain timestamp is the honest description.
     */
    public boolean isFreshFromWake() {
        if (wokeAt == 0L || lastTileChangeAt < wokeAt)
            return false;
        if (lastTileChangeAt - wokeAt > 30000L)
            return false;
        return System.currentTimeMillis() - lastTileChangeAt < 120000L;
    }

    /** True when tiles are overdue: nothing new for several intervals running. */
    public boolean isStale() {
        if (!isOn() || !screenOn || lastTileChangeAt == 0L)
            return false;
        return System.currentTimeMillis() - lastTileChangeAt > Math.max(3L * intervalMs, 180000L);
    }

    /** True when this source has no cache to watch, so freshness cannot be reported. */
    public boolean isFreshnessKnown() {
        return tileCache != null;
    }

    // ------------------------------------------------------------- screen on and off

    /**
     * Follow the display, because the display is what decides whether refreshing is
     * possible or worth doing.
     *
     * <p>Screen off: stop. The surface does not render with the display off, so a pump
     * would achieve nothing except waking the CPU on a schedule.
     *
     * <p>Screen on: refresh at once, then resume the beat. Waiting out the remainder of
     * an interval would mean the operator looks at stale tiles for up to a minute at
     * exactly the moment they picked the device up to look at it.
     */
    private void watchScreen() {
        if (screenWatch != null)
            return;
        screenOn = isScreenOn();
        screenWatch = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent != null ? intent.getAction() : null;
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOn = false;
                    stopHeartbeat();
                    Log.d(TAG, "screen off: holding");
                    fire();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)
                        || Intent.ACTION_USER_PRESENT.equals(action)) {
                    screenOn = true;
                    wokeAt = System.currentTimeMillis();
                    Log.d(TAG, action + ": refreshing");
                    // The renderer needs a moment after wake before a requested frame
                    // reaches the surface; refreshing into a surface that is not up yet
                    // silently does nothing.
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isOn() && screenOn)
                                refreshNow();
                        }
                    }, 750L);
                    stopHeartbeat();
                    startHeartbeat();
                }
            }
        };
        final IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        // Screen-on is not the same moment as visible-to-the-operator: a device wakes to
        // the lock screen, where ATAK is not rendering, so a refresh fired then lands
        // nowhere. Measured on the XCover Pro, refreshing on screen-on alone took 25 s to
        // reach the cache; the unlock is the moment the map is actually being looked at.
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            hostContext().registerReceiver(screenWatch, f);
        } catch (Throwable t) {
            // Without this the overlay still refreshes on its interval whenever the
            // screen happens to be on; it just will not catch the wake promptly.
            Log.w(TAG, "cannot watch the screen", t);
            screenWatch = null;
        }
    }

    private void unwatchScreen() {
        if (screenWatch == null)
            return;
        try {
            hostContext().unregisterReceiver(screenWatch);
        } catch (Throwable t) {
            Log.w(TAG, "unregistering screen watch failed", t);
        }
        screenWatch = null;
    }

    private boolean isScreenOn() {
        try {
            final PowerManager pm = (PowerManager)
                    hostContext().getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** True when the overlay is on but deliberately holding because the screen is off. */
    public boolean isHoldingForScreen() {
        return isOn() && !screenOn;
    }

    /**
     * Force a draw pump.
     *
     * The surface renders when dirty, so a still map issues no draws and
     * {@code MobacTileReader.start()} — the only thing that expires tiles and bumps the
     * tile version — never runs. Marking the surface dirty and asking for a refresh
     * gives it that draw. Everything downstream is ATAK's own machinery.
     */
    private void pump() {
        try {
            final SurfacePump p = SurfacePump.of(mapView);
            if (p != null)
                p.pump();
            lastPumpAt = System.currentTimeMillis();
            pumps++;
            fire();
        } catch (Throwable t) {
            Log.w(TAG, "pump failed", t);
        }
    }

    // -------------------------------------------------------------------- plumbing

    /**
     * The interval the source itself asked for, if it named one.
     *
     * {@code <tileUpdate>} lands in the map source rather than the descriptor, so rather
     * than re-parse the XML this reads it back off the descriptor when ATAK put it there
     * and falls back to the source's declared default otherwise. One plugin then serves
     * traffic, weather radar and anything else with a short life without hardcoding.
     */
    private static long intervalOf(DatasetDescriptor d, Source src) {
        try {
            final String v = d.getExtraData("tileUpdate");
            if (v != null)
                return Long.parseLong(v.trim());
        } catch (Throwable ignored) {
            // Not there, or not a number. The source's own default is the answer.
        }
        return src.defaultIntervalMs;
    }

    /**
     * Copy the bundled XML somewhere ATAK can open it by path.
     *
     * The destination is the <em>host</em> context's cache directory, never the plugin
     * context's. The plugin context's own files and cache directories live inside the
     * plugin package's data dir, which ATAK's process runs under a different uid and
     * cannot create: it fails with ENOENT and every write silently vanishes. The assets
     * are still read through the plugin context — that part works — but nothing may be
     * written there.
     */
    private File stageAsset(Source src) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            final File dir = new File(hostContext().getCacheDir(), "traffic");
            if (!dir.exists() && !dir.mkdirs())
                return null;
            final File dest = new File(dir, new File(src.asset).getName());
            in = pluginContext.getAssets().open(src.asset);
            out = new java.io.FileOutputStream(dest);
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            out.flush();
            return dest;
        } catch (Throwable t) {
            Log.e(TAG, "staging " + src.asset + " failed", t);
            return null;
        } finally {
            close(in);
            close(out);
        }
    }

    /** ATAK's own context — the one with a writable data directory. */
    private Context hostContext() {
        return mapView != null ? mapView.getContext() : pluginContext;
    }

    private static void close(java.io.Closeable c) {
        if (c == null)
            return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // Closing is best-effort; a failure here says nothing the caller can act on.
        }
    }

    private static String describe(Throwable t) {
        final String m = t.getMessage();
        return t.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }

    private void fire() {
        final Listener l = listener;
        if (l != null)
            l.onOverlayChanged(this);
    }
}
