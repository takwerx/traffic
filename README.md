ATAK Plugin — Traffic

**Download Traffic 0.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/traffic/releases/download/v0.5/ATAK-Plugin-Traffic-0.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/traffic/releases/download/v0.5/ATAK-Plugin-Traffic-0.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/traffic/releases/download/v0.5/ATAK-Plugin-Traffic-0.5--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/traffic/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/traffic/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

Live traffic overlay for ATAK. Draws current road traffic above whatever base
map the operator has already selected, and keeps it refreshing while the map
sits still.

ATAK can already display an online tile source, and a MOBAC source can declare a
refresh interval. Two things stop that from producing a live overlay. The
interval is only honored while the map is being drawn, so a device that pans
stays current and a device sitting on a desk silently goes stale -- which is the
state an operator is usually in when they want to read traffic. And a composited
multi-layer source is built with no refresh interval at all, so the one
arrangement that puts traffic over an operator's own base map is the one
arrangement that cannot refresh.

This plugin owns its own overlay layer and drives the refresh itself.

Capabilities:

  - Traffic drawn above the operator's chosen base map, which stays visible
    underneath. Imagery, topo or vector -- the overlay does not replace it.
  - Refreshes on a wall clock while the map is untouched, at an interval the
    operator selects (15 seconds to 10 minutes).
  - Holds while the screen is off, and refreshes immediately on wake. Nothing
    can render with the display off, so refreshing then would spend battery to
    no purpose; what matters is being current the moment the device is picked
    up. The status says so explicitly when tiles arrived because the device was
    woken, rather than leaving the operator to infer it from a recent timestamp.
  - Manual refresh that takes effect at once rather than at the next interval.
  - A status line reporting when tiles genuinely last arrived, read from the
    tile cache rather than from the plugin's own timer, so a stalled network
    shows as a timestamp that stops moving rather than one that keeps ticking
    over a frozen picture.
  - One tap on and off, from the plugin pane, with the state shown as a
    color-coded ON or OFF rather than buried in a sentence.

_________________________________________________________________
STATUS

Version 0.5. Verified on ATAK-CIV 5.7.0.5.

Exercised on hardware: Samsung Galaxy XCover Pro, ATAK-CIV 5.8.0.3, as a release
(proguard) build. Verified on device, with the map untouched throughout:

  - Refresh on a still map at the selected interval, confirmed against tile
    arrival rather than screenshots.
  - No refresh while the screen is off, across several interval boundaries.
  - Refresh within about three seconds of the device being unlocked.
  - Manual refresh taking effect a full interval before the next scheduled one,
    with the configured interval correctly restored afterwards.

Prepared for tak.gov third-party submission.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/traffic/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only, and only while the operator has the overlay
  turned on and the device screen is awake.

  The plugin streams map tiles from a Google tile endpoint, mt1.google.com. This
  is an undocumented, unauthenticated endpoint -- no API key is used or
  required. Requests carry no credentials and no device identity.

  What leaves the device is the set of tile coordinates covering the visible map
  area, at the operator's selected refresh interval. Tile coordinates are
  derived from the map viewport, so the operator's area of interest is inferable
  by the tile provider at the resolution of a map tile. This is inherent to any
  streamed tile source and is stated here so it is not a surprise.

  Traffic is requested at whatever interval the operator selects, default 60
  seconds. Turning the overlay off, or the screen off, stops all traffic
  immediately.

  No inbound ports. No listening sockets. No traffic to or from the TAK server,
  and no CoT is generated or consumed. With the overlay off the plugin makes no
  network calls at all.

  Tiles are cached by ATAK's own imagery cache, in the standard imagecache
  location, and are subject to ATAK's cache management.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8.
  A network connection while the overlay is in use. Traffic is live data and has
  no meaningful offline mode -- cached tiles are, by definition, old traffic.
  Negligible storage: tiles land in ATAK's existing imagery cache.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any Android device supported by ATAK. No additional or external hardware, no
  sensors, no peripherals.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. Set sdk.path in local.properties to an unpacked
  ATAK CIV SDK, then:

      ./gradlew assembleCivDebug
      ./gradlew assembleCivRelease

  ext.ATAK_VERSION in app/build.gradle selects the ATAK release to target.

  The tile source is a MOBAC custom map source XML shipped in the plugin's
  assets (app/src/main/assets/mapsources/). Adding a source is a matter of
  adding an XML file and one list entry; the refresh mechanism is not specific
  to traffic and applies to any short-lived tile source.

_________________________________________________________________
DEVELOPER NOTES

  The refresh mechanism is a heartbeat, not a cache operation. ATAK's map
  surface renders on demand -- GLMapSurface sets RENDERMODE_WHEN_DIRTY -- and
  MobacTileReader.start(), which is the only place that expires cached tiles and
  advances the tile version, is called from the draw path. With a still map
  there are no draws, so no interval however short will fire. The plugin forces
  a draw pump on a timer (SurfaceRendererControl.markDirty plus
  RenderContext.requestRefresh) and lets ATAK's own machinery do the expiry, the
  version bump, the refetch and the repaint.

  Expiring tiles is not sufficient on its own. GLQuadTileNode4 re-reads a tile
  when its tile version changes, and the version is incremented only inside the
  interval gate in MobacTileReader.start(). A refresh requested partway through
  an interval therefore expires the cache and changes nothing visible until the
  interval elapses. Forcing an immediate refresh means briefly setting the
  refresh interval to 1 ms so the gate passes, pumping, and restoring the real
  interval -- left dropped, the layer refetches every frame.

  The refresh interval is set on the live layer at runtime through
  OnlineImageryExtension, obtained with Layer2.getExtension. This is what makes
  a composited overlay refreshable at all, since the multi-layer MOBAC parser
  passes no interval. Note that OnlineImageryExtension is marked deprecated
  (since 5.3) while remaining in use by ATAK's own layer manager in 5.8; it is
  the version-sensitive part of this plugin and should be re-checked on any ATAK
  upgrade.

  The overlay is a plugin-owned DatasetRasterLayer2 over a RuntimeRasterDataStore,
  added at MapView.RenderStack.RASTER_OVERLAYS. That is what puts it above the
  base map: ATAK's Native and Mobile imagery are mutually exclusive cards of a
  single CardLayer, so selecting one deselects the other and no arrangement
  within the layer manager can stack them.

  Freshness is measured from the tile cache file's modification time, whose path
  comes from the layer descriptor's offlineCache extra. A successful fetch always
  writes, whether or not the tile content changed; a failed fetch writes nothing.
  So the timestamp means "tiles arrived", which is the question worth answering,
  and a dead network freezes it. It does not mean the traffic picture changed.

  Passing a null working directory when creating the dataset descriptor does not
  avoid the disk cache: MobacMapSourceLayerInfoSpi also honors the global
  ConfigOptions imagery.offline-cache-dir, which ATAK sets. The cache, and its
  one-week expiry floor, apply regardless -- defeating that floor is what the
  refresh interval is for.

  Anything written by the plugin must go to ATAK's context, not the plugin
  context. The plugin package's data directory cannot be created by ATAK's
  process and every write there fails with ENOENT. Assets are still read through
  the plugin context.

  A plugin resource id passed to a dialog built on ATAK's context resolves
  against ATAK's resources, not the plugin's, and silently produces the wrong
  string. Resolve strings with pluginContext.getString before handing them to a
  dialog. Dialogs themselves must use ATAK's context, which has a window token;
  the plugin context does not.
