#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Traffic",
   plugin-version: "0.5",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

#toolbox.side-by-side(columns: (1.5fr, 9fr))[
  #image("plugin_icon.png", width: 85%)
][
Traffic draws current road traffic above whatever base map you already have,
and keeps it refreshing while the map sits still. Your imagery, topo or vector
base map stays visible underneath -- the overlay does not replace it.
]

#v(6pt)

ATAK can already show an online tile source, and a MOBAC source can declare a
refresh interval, but two things stop that being a live overlay. The interval
is only honoured while the map is being drawn, so a device panning stays
current while one sitting on a desk quietly goes stale -- which is the state an
operator is usually in when they want to read traffic. And a composited
multi-layer source is built with no interval at all, so the one arrangement
that puts traffic over your own base map is the one that cannot refresh.

#v(6pt)

This plugin owns its overlay and drives the refresh itself.

#v(6pt)

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("03.jpg", width: 96%)
][
  Traffic drawn over the operator's own base map, which stays visible
  underneath it.
]
]

#tak-slide[
= Before you start

- *Match the plugin to your ATAK version.* Builds are tied to the ATAK release
  they were built against; 5.6, 5.7 and 5.8 builds are published. A mismatched
  build will not load, and the failure does not look like a version problem.
- *It costs data.* A refresh fetches the tiles covering your screen. Zoomed out
  over a city at a 15-second interval is the expensive case; a longer interval
  or a tighter zoom costs less.
- *Coverage is wherever the provider has data.* Motorways and major roads are
  well covered in populated areas. Expect little or nothing on forest roads and
  in the back country.

#v(6pt)

Load it from ATAK's Plugins manager (TAK Package Mgmt) like any other plugin,
then open it from the toolbar.
]

#tak-slide[
= Turning it on

The pane has two titled controls side by side. *Traffic Overlay* turns the
overlay on and off. *Persistent Overlay* decides whether it comes back by
itself after ATAK restarts. Each reads ON in green or OFF.

#v(6pt)

Below them, *Refresh now* fetches immediately, and *Interval* sets how often it
refreshes on its own -- 15 seconds to 10 minutes.

#v(6pt)

Turning the overlay on replaces whatever the plugin was drawing before. It
never stacks two.

#v(6pt)

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("01.png", width: 92%)

  Everything off, and what each control does.
][
  #image("04.png", width: 92%)

  The refresh intervals, 15 seconds to 10 minutes.
]
]

#tak-slide[
= Persistent Overlay

By default the overlay starts off every time ATAK launches, the same as ATAK's
own grids and overlays.

#v(6pt)

Set *Persistent Overlay* to ON and the plugin remembers whether the overlay was
running, and which source it was showing, and puts it back on the next launch.
For a crew who watch traffic all shift, that is one less thing to do every time
the app restarts.

#v(6pt)

It is off by default on purpose: restoring means the feed starts fetching the
moment ATAK launches, which is not a decision to make for you on a metered
connection or a cold start in the field.

#v(6pt)

A feed that failed to load is not restored as on. What is remembered is what
the overlay actually did, not what was asked of it.

#v(6pt)

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("02.png", width: 94%)
][
  Both on. The status underneath says how often it is refreshing and when the
  tiles last arrived.
]
]

#tak-slide[
= Leave it alone -- that is the point

Put the device down and do not touch it. The overlay keeps refreshing on its
own and the *Last refresh* time keeps advancing. An ordinary online map source
left untouched quietly freezes at whatever the last redraw left behind.

#v(6pt)

*Watch the timestamp, not the colours.* Traffic that has not changed looks
identical refresh after refresh -- at three in the morning it will be green for
hours -- so the timestamp is how you know it is live.

#v(6pt)

*"Last refresh" means tiles arrived*, not that the traffic picture changed. If
the network drops, that time stops moving and a warning appears beneath it.
That is the difference between live and a picture of five minutes ago.
]

#tak-slide[
= Screen off, and why nothing is wrong

When the screen goes off the overlay stops refreshing and *no tiles are
requested at all*. It costs no battery and no data while nobody is looking at
it.

#v(6pt)

The moment you wake the device it refreshes, typically within about three
seconds of unlocking, before you have finished reading the map. The status says
so plainly for a couple of minutes afterwards:

#v(6pt)

  *Refreshed on wake at 12:43:56*

#v(6pt)

So if you pick the device up and see that line, what you are looking at arrived
after you woke it -- not before you put it down.

#v(6pt)

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("05.png", width: 94%)
][
  Woken at 16:26:52, and the overlay had refreshed before the panel was even
  opened.
]
]

#tak-slide[
= Good to know

- *Traffic that does not change is not a fault.* Watch the timestamp.
- *Zoom matters.* Traffic draws from about zoom 10 down to street level. Zoomed
  far out you will see motorways only.
- *Your base map is untouched.* Turning the overlay off removes it, stops the
  refresh and stops all network activity; the status returns to OFF.
- *After updating the plugin* ATAK unloads it. Reload it from the Plugins
  manager.

#v(6pt)

*Questions and problems:* https://github.com/takwerx/traffic/issues
]
