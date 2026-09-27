#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Traffic",
   plugin-version: "0.8",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

#toolbox.side-by-side(columns: (1.5fr, 9fr))[
  #image("plugin_icon.png", width: 85%)
][
Traffic puts two things on your map. *Live traffic*: the colored road lines,
drawn above whatever base map you already use and kept refreshing while the
map sits still. *Road incidents*: crashes, closures, lane closures, road work,
hazards and message signs from the traveler information system of every US
state, drawn as icons.
]

#v(4pt)

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("02.jpg", height: 6.8cm)
][
  Southern California: traffic colors, highway shields on top of them, and the
  incident icons. The pane on the right turns each part on and off.
]
]

#tak-slide[
= Before you start

- *Match the plugin to your ATAK version.* Builds are tied to the ATAK release
  they were built against; 5.6, 5.7 and 5.8 builds are published. A mismatched
  build will not load, and the failure does not look like a version problem.
- *It uses data.* Traffic fetches the map tiles covering your screen at the
  interval you pick. Incidents are small: a short check every 15 seconds, and
  a state's file only when something in it changed.
- *Traffic coverage* is wherever the provider has data: good on highways and
  major roads, little or nothing on forest roads. *Incident coverage* is what
  each state publishes; some states publish more than others.
- Incidents come from a takwerx server that gathers every state's public feed.
  Your device talks only to that server and to the traffic tile provider.

#v(4pt)

Load it from ATAK's Plugins manager like any other plugin, then open it from
the toolbar:

#v(2pt)
#image("01.jpg", width: 100%)
]

#tak-slide[
= The main screen

#toolbox.side-by-side(columns: (4fr, 4fr, 5fr))[
  #image("03.png", width: 100%)
][
  #image("04.png", width: 100%)
][
*Traffic Overlay* turns the traffic colors on and off. *Persistent Overlay*
decides whether they come back by themselves after ATAK restarts. Each reads
ON in green or OFF in red.

#v(4pt)

*Refresh now* fetches at once; *Interval* sets how often it refreshes on its
own. The line under them says when tiles last arrived.

#v(4pt)

*511 Incidents* turns the incident icons on and off, and *Settings* opens their
page: which states, how far in you must zoom, what area and which types.

#v(4pt)

The line under them names the states shown and when they were last checked.
]
]

#tak-slide[
= How often traffic refreshes

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("05.png", width: 96%)
][
Pick 15 seconds to 10 minutes. Shorter is fresher and costs more data;
zoomed out over a city at 15 seconds is the expensive case.

#v(6pt)

*Persistent Overlay.* By default the traffic overlay starts off every time ATAK
launches, like ATAK's own grids. Set Persistent Overlay to ON and it comes back
on by itself. It is off by default on purpose: restoring means fetching the
moment ATAK launches, which is not a decision to make for you on a metered
connection.

#v(6pt)

The incident icons remember on or off by themselves.
]
]

#tak-slide[
= Your map stays underneath

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("06.jpg", height: 9.6cm)
][
Traffic is drawn above the base map you chose -- imagery, topo or street --
and never replaces it.

#v(6pt)

*Highway shields are drawn on top of the traffic.* On an ordinary traffic
layer the colored line covers the route number; here the shields sit above it,
so you can read which road is red.

#v(6pt)

On a base map that has its own shields you may see a road's shield twice. That
is the two layers agreeing, not a fault.
]
]

#tak-slide[
= Leave it alone -- that is the point

Put the device down and do not touch it. The overlay keeps refreshing on its
own and the *Last refresh* time keeps advancing. An ordinary online map source
left untouched quietly freezes at whatever the last redraw left behind.

#v(6pt)

*Watch the timestamp, not the colors.* Traffic that has not changed looks the
same refresh after refresh -- at three in the morning it will be green for
hours -- so the timestamp is how you know it is live.

#v(6pt)

*"Last refresh" means tiles arrived*, not that the traffic changed. If the
network drops, that time stops moving and a warning appears beneath it.

#v(6pt)

*Screen off:* nothing is fetched at all, traffic or incidents, so it costs no
battery and no data. When you wake the device both refresh within a few
seconds, and the status says "Refreshed on wake" for a couple of minutes.
]

#tak-slide[
= Road incidents and their icons

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("10.png", width: 96%)
][
Each type has its own icon. The same tiles in Settings switch a type on or off.

#v(4pt)

- *Crashes* and *Vehicle fires*
- *Road closed*: the road or a ramp is closed
- *Lanes closed*: the road is open with lanes closed
- *Road work*: work zones and construction
- *Hazards*: debris, stalled vehicles, damage, rock fall
- *Chains and snow*, *Weather*
- *Message signs*: what a highway sign says right now
- *Notices*: anything else the agency posted

#v(4pt)

Icons only, never road lines: the traffic colors already show the roads.
]
]

#tak-slide[
= Your states

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("12.png", width: 96%)
][
Incidents are shown for the states you pick, in Settings under *States*. The
first time you turn incidents on, the state you are in is picked for you.

#v(6pt)

Pick every state you work in or drive through. Nothing is downloaded for a
state until the map is over it, unless Area is set to Everything.

#v(6pt)

Some states publish more than others. Rhode Island, for one, publishes only
its planned closures for the week, and the details say so.
]
]

#tak-slide[
= Over a state you do not follow

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("17.jpg", width: 100%)
][
When the map is over a state you have not picked, the pane says so and offers
it: *Add Arizona* adds it in one tap.

#v(6pt)

The offer names the state under the middle of the map, even when one of your
states is close by.
]
]

#tak-slide[
= The Settings page

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("07.png", width: 96%)
][
Each row says what it is set to. The arrow beside a row opens it.

#v(6pt)

- *States*: which states you follow.
- *Zoom gate*: how far in you must zoom before icons are drawn.
- *Area*: everything, what is in view, or a distance around you or the map
  center.
- *Types*: which kinds of incident are shown.

#v(6pt)

*Back* returns to the main screen.
]
]

#tak-slide[
= Zoom gate

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("11.jpg", width: 100%)
][
  #image("08.png", width: 100%)
  #v(4pt)
  Zoomed out past the gate, no icons are drawn and the line says so: "Zoom in
  to see incidents. Shown at 15 mi or closer."

  #v(4pt)

  *Use this zoom* sets the gate to what the scale bar reads now; the button
  beside it picks from a list, including Always.
]
]

#tak-slide[
= Area

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("09.png", width: 96%)
][
*What is in view* shows the incidents on screen, the default.

#v(4pt)

Slide right for a *distance*: only incidents within that distance are shown,
measured from *My Location* or from the *map center*.

#v(4pt)

*Use this extent* sets the distance to what is on screen now. *Presets* has
common distances, What is in view and *Everything*.

#v(4pt)

With a distance around you, the main screen says so, so a hidden part of the
map is never a surprise.
]
]

#tak-slide[
= Tapping an incident

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("13.jpg", height: 9.6cm)
][
Tap an icon and its title shows above it with the menu around it. The list
item at the bottom opens its details.

#v(6pt)

Where icons sit on top of each other, ATAK first asks which one you meant.
]
]

#tak-slide[
= What the details say

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("14.jpg", width: 100%)
][
The type, a short title, where, and when it was reported and last updated.

#v(4pt)

For California Highway Patrol incidents, the *Units* list: assigned, en route,
at scene.

#v(4pt)

The page keeps updating while it is open. An incident that clears is marked
so, rather than vanishing under you.
]
]

#tak-slide[
= Message signs and closures

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("15.png", width: 100%)
  #v(2pt)
  A message sign is drawn as it reads on the road.
][
  #image("16.jpg", width: 100%)
  #v(2pt)
  A closure: what is closed, why, and when it is expected to open.
]
]

#tak-slide[
= Good to know

- *Traffic that does not change is not a fault.* Watch the timestamp.
- *A crash more than six hours old* is shown as what it left behind -- lanes
  closed, road closed or a hazard -- and its details say when it was reported.
- *Times in the notes* are the agency's own local time.
- *After updating the plugin* ATAK unloads it; load it again from the Plugins
  manager.
- *This manual* is also in ATAK's Settings, under Tool Preferences, Traffic:

#v(2pt)
#image("18.png", width: 100%)

#v(4pt)

*Questions and problems:* https://github.com/takwerx/traffic/issues
]
