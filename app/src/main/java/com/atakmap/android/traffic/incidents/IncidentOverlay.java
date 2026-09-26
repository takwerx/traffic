package com.atakmap.android.traffic.incidents;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.Style;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The incidents on the map: one feature store, one layer, one entry in Overlay Manager
 * with a switch per kind (Crashes, Closures, Road work...).
 *
 * <p>IPAWS's {@code AlertOverlay}, cut down, with two deliberate differences:
 *
 * <ul>
 *   <li><b>The store is never disposed.</b> The renderer queries it from a worker of
 *       its own, and a closed store there aborts the whole ATAK process through JNI
 *       (Atmosphere, 2026-09-25). A leaked handle costs nothing beside that.</li>
 *   <li><b>A fresh file every session.</b> Yesterday's crashes must never be on the
 *       map before the first poll answers, and an old store cannot be emptied safely
 *       while the previous plugin load may still hold it. A new name sidesteps both;
 *       older files are removed at attach.</li>
 * </ul>
 */
public class IncidentOverlay {

    private static final String TAG = "Traffic511";
    public static final String TITLE = "511 Incidents";
    static final String META_FEATURE = "featureid";
    static final String META_MARK = "traffic_incident";
    /** The incident's own id ("CHP:260926SA0967"), stable across rewrites. */
    static final String META_ID = "traffic_incident_id";

    private final MapView mapView;
    private final Context pluginContext;
    private final Object lock = new Object();

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private final IncidentIcons icons = new IncidentIcons();
    /** The feature for each incident on the map, by the incident's own id. Under lock. */
    private final Map<String, Row> rows = new HashMap<>();
    /** One feature set per kind, made on the first pass. Under lock. */
    private final Map<String, Long> sets = new HashMap<>();
    /**
     * The incident id behind each recently tapped item, by the item's uid. The radial's
     * Details button broadcasts together with UNFOCUS and HIDE_MENU, and those can take
     * the temporary tapped item off the map before our receiver looks it up
     * ("details: item=false", s10-dev-2, 2026-09-26). Remembering the id when the item
     * is made takes that race out entirely.
     */
    private final Map<String, String> tapped = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 64;
                }
            });
    private boolean previewed;
    private volatile boolean shown = true;
    private volatile int count;

    public IncidentOverlay(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
    }

    public boolean isAttached() {
        synchronized (lock) {
            return store != null;
        }
    }

    public int count() {
        return count;
    }

    FeatureDataStore2 store() {
        return store;
    }

    /** The incident id behind a recently tapped item's uid, or null. */
    String incidentIdForUid(String uid) {
        return uid == null ? null : tapped.get(uid);
    }

    /** Main thread. Cheap: opens an empty file and registers a layer. */
    public void attach() throws Exception {
        synchronized (lock) {
            if (store != null)
                return;
            final File dir = FileSystemUtils.getItem("tools/traffic/511");
            if (!dir.isDirectory() && !dir.mkdirs())
                throw new java.io.IOException("cannot create " + dir);
            final File nomedia = new File(dir, ".nomedia");
            if (!nomedia.exists() && !nomedia.createNewFile())
                Log.w(TAG, "could not write " + nomedia);
            final String name = "incidents-" + System.currentTimeMillis() + ".db";
            purgeOldStores(dir, name);

            store = new FeatureSetDatabase2(new File(dir, name));
            rows.clear();
            sets.clear();
            final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                    new FeatureDataStore2.FeatureQueryParameters();
            visibleOnly.visibleOnly = true;
            layer = new FeatureLayer3(TITLE, store, visibleOnly);
            overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                    TITLE, "file://asset/nothing", new Query(layer), null, null);
            // addOverlay, not addFilesOverlay: the files group never lists a plugin's
            // own overlay (IPAWS, 2026-09-16). The add says whether it took.
            final boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            Log.d(TAG, "overlay registration: added=" + added + " findable="
                    + (mapView.getMapOverlayManager().getOverlay(overlay.getIdentifier()) != null));
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
        }
    }

    /** Main thread. Leaves the store open on purpose: see the class comment. */
    public void detach() {
        synchronized (lock) {
            try {
                if (layer != null)
                    mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
                if (overlay != null)
                    mapView.getMapOverlayManager().removeOverlay(overlay);
            } catch (Exception e) {
                Log.w(TAG, "detach failed", e);
            }
            layer = null;
            overlay = null;
            store = null;
            count = 0;
        }
    }

    /**
     * 511 ON / OFF, on the main thread. Hiding the layer takes the dots off on the
     * next frame; the empty rewrite that follows OFF (on the worker) is what frees
     * them. A tap must not find what the operator just switched off.
     */
    public void setShown(boolean on) {
        shown = on;
        final FeatureLayer3 l = layer;
        if (l != null)
            l.setVisible(on);
    }

    /**
     * Brings the map in line with these incidents: new ones added, changed ones updated
     * in place, cleared ones removed, and everything else left exactly as it was.
     *
     * <p>In place, not delete-and-reinsert, because a feature's store id is its identity
     * to ATAK. A tapped item's uid is that id, and ATAK finds the item again by it --
     * for the radial's Details, and for a pick from the Select Item chooser. Rewriting
     * everything every minute renumbered every feature, so a tap made before a poll
     * pointed at nothing after it: Details said "cleared" for incidents still on the
     * map, and chooser picks opened nothing (s10-dev-2, 2026-09-26).
     *
     * <p>One modify lock for the whole pass, so ATAK re-reads the store once. Worker
     * thread only: a loop of store writes on main is an ANR (Atmosphere, 2026-09-25).
     * An empty list is honored -- a server that answered "nothing" means nothing is
     * happening; a poll that failed never calls this.
     */
    public void rewrite(List<Incident> incidents) {
        synchronized (lock) {
            if (store == null)
                return;
            boolean locked = false;
            try {
                store.acquireModifyLock(true);
                locked = true;
                // Every kind gets its set up front, so Overlay Manager keeps a stable
                // list rather than rows that come and go with the traffic.
                if (sets.isEmpty())
                    for (String kind : IncidentStyles.KINDS)
                        sets.put(kind, newSet(IncidentStyles.setName(kind)));
                if (com.atakmap.android.traffic.plugin.BuildConfig.DEBUG && !previewed) {
                    previewed = true;
                    icons.writePreview();
                }
                final java.util.Set<String> seen = new java.util.HashSet<>();
                int added = 0, changed = 0, removed = 0;
                for (Incident in : incidents) {
                    if (!seen.add(in.id))
                        continue;
                    final String sig = in.signature();
                    final Row row = rows.get(in.id);
                    if (row != null && row.kind.equals(in.kind)) {
                        if (!row.sig.equals(sig)) {
                            final Geometry g = geometry(in);
                            if (g == null)
                                continue;
                            store.updateFeature(row.fid,
                                    FeatureDataStore2.PROPERTY_FEATURE_NAME
                                            | FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                                            | FeatureDataStore2.PROPERTY_FEATURE_STYLE
                                            | FeatureDataStore2.PROPERTY_FEATURE_ATTRIBUTES,
                                    in.title(), g, style(in), attributes(in),
                                    FeatureDataStore2.UPDATE_ATTRIBUTES_SET);
                            rows.put(in.id, new Row(row.fid, in.kind, sig));
                            changed++;
                        }
                        continue;
                    }
                    // New, or it changed kind (a crash that became a closure): its set
                    // is different, so it gets a new feature.
                    if (row != null) {
                        store.deleteFeature(row.fid);
                        rows.remove(in.id);
                    }
                    final Geometry g = geometry(in);
                    if (g == null)
                        continue;
                    final long fid = store.insertFeature(new Feature(sets.get(in.kind),
                            in.title(), g, style(in), attributes(in),
                            Feature.AltitudeMode.ClampToGround, 0d));
                    rows.put(in.id, new Row(fid, in.kind, sig));
                    added++;
                }
                final java.util.Iterator<Map.Entry<String, Row>> it = rows.entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry<String, Row> e = it.next();
                    if (seen.contains(e.getKey()))
                        continue;
                    store.deleteFeature(e.getValue().fid);
                    it.remove();
                    removed++;
                }
                count = rows.size();
                Log.d(TAG, count + " on the map: " + added + " new, " + changed
                        + " changed, " + removed + " cleared");
            } catch (Exception e) {
                Log.w(TAG, "update failed", e);
            } finally {
                if (locked)
                    store.releaseModifyLock();
            }
        }
    }

    private Style style(Incident in) {
        return in.point ? IncidentStyles.point(in.kind, icons.uri(in.kind))
                : IncidentStyles.line(in.kind);
    }

    /** One incident's feature: its store id, which set it is in, and what it said. */
    private static final class Row {
        final long fid;
        final String kind;
        final String sig;

        Row(long fid, String kind, String sig) {
            this.fid = fid;
            this.kind = kind;
            this.sig = sig;
        }
    }

    /**
     * Flat on purpose: a collection nested inside a collection can come back from the
     * store as one point at 0,0 (IPAWS, 2026-09-25).
     */
    private static Geometry geometry(Incident in) {
        if (in.point) {
            final double[] xy = in.parts.get(0);
            return new Point(xy[0], xy[1]);
        }
        if (in.parts.size() == 1)
            return line(in.parts.get(0));
        final GeometryCollection gc = new GeometryCollection(2);
        for (double[] part : in.parts)
            gc.addGeometry(line(part));
        return gc;
    }

    private static LineString line(double[] xy) {
        final LineString ls = new LineString(2);
        for (int i = 0; i + 1 < xy.length; i += 2)
            ls.addPoint(xy[i], xy[i + 1]); // longitude first
        return ls;
    }

    /** What the details pane reads back by feature id. */
    private static AttributeSet attributes(Incident in) {
        final AttributeSet a = new AttributeSet();
        a.setAttribute("id", in.id);
        a.setAttribute("kind", in.kind);
        a.setAttribute("headline", in.title());
        a.setAttribute("where", in.where);
        a.setAttribute("area", in.area);
        a.setAttribute("agency", in.agency);
        a.setAttribute("status", in.status);
        a.setAttribute("reported", in.reported);
        a.setAttribute("updated", in.updated);
        a.setAttribute("details", in.details);
        return a;
    }

    private long newSet(String name) throws Exception {
        // Double.MAX_VALUE, 0d: drawn at every zoom. 0d as the first argument reads
        // like "no gate" and means "never" (IPAWS 0.1, 2026-09-16).
        final long id = store.insertFeatureSet(
                new FeatureSet("Traffic", "511", name, Double.MAX_VALUE, 0d));
        store.setFeatureSetVisible(id, true);
        return id;
    }

    /** An incident's own id from its feature, or null. */
    private String incidentId(long fid) {
        final FeatureDataStore2 s = store;
        if (s == null)
            return null;
        com.atakmap.map.layer.feature.FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p =
                    new FeatureDataStore2.FeatureQueryParameters();
            p.ids = java.util.Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE;
            p.limit = 1;
            c = s.queryFeatures(p);
            if (c.moveToNext()) {
                final AttributeSet a = c.get().getAttributes();
                return a != null && a.containsAttribute("id") ? a.getStringAttribute("id") : null;
            }
        } catch (Exception e) {
            Log.w(TAG, "id of feature " + fid + " failed", e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                    // Nothing useful to do with a cursor that will not close.
                }
        }
        return null;
    }

    private static void purgeOldStores(File dir, String keep) {
        final File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            final String n = f.getName();
            if (n.startsWith("incidents-") && !n.startsWith(keep) && !f.delete())
                Log.w(TAG, "could not remove " + f);
        }
    }

    /** Taps: our radial, the headline as the title, one row per incident. */
    private final class Query extends FeatureDataStoreDeepMapItemQuery {

        Query(FeatureLayer3 layer) {
            super(layer);
        }

        @Override
        protected MapItem featureToMapItem(Feature feature) {
            final MapItem item = super.featureToMapItem(feature);
            item.setMetaLong(META_FEATURE, feature.getId());
            item.setMetaString(META_MARK, "1");
            // Our own radial: without one ATAK opens its generic feature metadata,
            // a second details screen with no way back.
            item.setMetaString("menu", PluginMenuParser.getMenu(
                    pluginContext, "menu/incident.xml"));
            item.setMetaString("title", feature.getName());
            item.setMetaString("callsign", feature.getName());
            // The hit-test query drops attributes, so the id is read back by feature id.
            final String id = incidentId(feature.getId());
            if (id != null) {
                item.setMetaString(META_ID, id);
                tapped.put(item.getUID(), id);
            }
            return item;
        }

        @Override
        public java.util.SortedSet<MapItem> deepHitTest(MapView view,
                com.atakmap.map.hittest.HitTestQueryParameters params,
                java.util.Map<com.atakmap.map.layer.Layer2,
                        java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
            return dedupe(super.deepHitTest(view, params, controls));
        }

        @Override
        public java.util.SortedSet<MapItem> deepHitTestItems(int x, int y,
                com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
            return dedupe(super.deepHitTestItems(x, y, point, view));
        }

        /** ATAK hands one feature back once per hit-test control; one row each. */
        private java.util.SortedSet<MapItem> dedupe(java.util.SortedSet<MapItem> hits) {
            if (hits == null)
                return null;
            final java.util.SortedSet<MapItem> out = new java.util.TreeSet<>(hits.comparator());
            if (!shown)
                return out;
            final java.util.Set<Long> seen = new java.util.HashSet<>();
            for (MapItem m : hits)
                if (seen.add(m.getMetaLong(META_FEATURE, -1)))
                    out.add(m);
            return out;
        }
    }
}
