package com.atakmap.android.traffic;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.MapRenderer3;
import com.atakmap.map.RenderContext;
import com.atakmap.map.layer.control.SurfaceRendererControl;

/**
 * Makes the map draw a frame it did not ask for.
 *
 * <p>The map surface renders on demand — {@code GLMapSurface} sets
 * {@code RENDERMODE_WHEN_DIRTY} — so a still map issues no draw pumps. That matters far
 * more than it sounds: {@code MobacTileReader.start()} is the only place that expires
 * cached tiles and bumps the tile version, and it is called from
 * {@code GLQuadTileNode4.draw}. No draw, no refresh, no matter what interval the source
 * asked for. Panning the map is what makes online imagery look live.
 *
 * <p>Two levers, used together because they are not the same lever:
 * <ul>
 *   <li>{@link SurfaceRendererControl#markDirty()} says the surface content is stale, so
 *       the renderer re-runs the layers that draw into it rather than recomposing what it
 *       already has.</li>
 *   <li>{@link RenderContext#requestRefresh()} asks for a frame at all, which a
 *       when-dirty surface otherwise will not produce.</li>
 * </ul>
 *
 * <p>Both are reached from {@code gov.tak.api} or from interfaces marked
 * {@code @DontObfuscate}, so neither depends on ATAK's release-to-release obfuscation
 * mapping.
 */
final class SurfacePump {

    private static final String TAG = "TrafficPump";

    private final RenderContext context;
    private final SurfaceRendererControl surface;

    private SurfacePump(RenderContext context, SurfaceRendererControl surface) {
        this.context = context;
        this.surface = surface;
    }

    /** @return a pump for this map view, or null if the renderer is not up yet. */
    static SurfacePump of(MapView mapView) {
        if (mapView == null)
            return null;
        try {
            final MapRenderer3 renderer = mapView.getRenderer3();
            if (renderer == null)
                return null;
            return new SurfacePump(renderer.getRenderContext(),
                    renderer.getControl(SurfaceRendererControl.class));
        } catch (Throwable t) {
            Log.w(TAG, "no renderer to pump", t);
            return null;
        }
    }

    void pump() {
        if (surface != null) {
            try {
                surface.markDirty();
            } catch (Throwable t) {
                Log.w(TAG, "markDirty failed", t);
            }
        }
        if (context != null) {
            try {
                context.requestRefresh();
            } catch (Throwable t) {
                Log.w(TAG, "requestRefresh failed", t);
            }
        }
    }
}
