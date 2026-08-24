# Traffic for ATAK — User Guide

**Version 0.2 · takwerx**

**Download Traffic 0.2** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.7:** _(release link — added when published)_
- **ATAK-CIV 5.8:** _(release link — added when published)_

All releases: _(repository releases page — added when published)_

Traffic draws live road traffic on top of the base map you are already using,
and keeps it current while the map sits still.

---

## 1. What this is for, in one minute

ATAK can display an online map source, and a map source can ask to be refreshed
on a timer. That sounds like it should already give you live traffic. It does
not, for two reasons.

**Refreshing only happens while the map is being drawn.** ATAK redraws the map
when something changes — you pan, you zoom, your position moves. A device
sitting on a dash mount or a desk is not drawing anything, so the refresh timer
never comes round and the traffic on screen is however old the last draw left
it. Pan the map and it updates, which is what makes the problem so easy to miss:
the moment you touch it to check, it looks fine.

**And a layered source cannot refresh at all.** The one arrangement you actually
want — traffic drawn over your own imagery or topo — is built without a refresh
interval in the first place.

This plugin owns its own overlay layer and drives the refresh itself, so the
map stays current whether or not anyone is touching it.

> **Traffic is live data.** There is no useful offline mode. Cached tiles are, by
> definition, old traffic — which is worse than no traffic, because it looks
> current.

---

## 2. Before you start

- **Match the plugin to your ATAK version.** Plugin builds are tied to the ATAK
  release they were built for (5.7 and 5.8 builds are published). A mismatched
  build will not load.
- **Install and load the plugin** through ATAK's *Plugins* manager (TAK Package
  Mgmt), the same as any other plugin.
- **Pick your base map first.** The overlay draws on top of whatever you already
  have selected, so choose your imagery, topo or vector map before turning
  traffic on. You can change it afterwards; traffic stays on top.
- **You need a network connection** while the overlay is in use. The plugin
  talks outbound over HTTPS (port 443) only, never to the TAK server, and
  generates no CoT.

---

## 3. Step by step

### Step 1 — Open the plugin

Traffic appears on the ATAK toolbar, and in the Tools list under the hamburger
menu at the top right. The icon is a traffic light over a road.

![Traffic in the Tools list](screenshots/1_plugin_in_tools_list.png)

### Step 2 — The Traffic pane

Three controls and a status line. The state is the first thing on it — **OFF**
in red, **ON** in green — so you can tell at a glance without reading. Everything
is off until you turn it on, so opening the pane changes nothing on the map.

![The Traffic pane, off](screenshots/2_pane_off.png)

### Step 3 — Turn it on

Tap **Turn On Traffic Overlay**. Traffic appears over your base map within a
few seconds — green where it is moving, amber and red where it is not.

![Traffic drawn over the base map](screenshots/3_overlay_on.png)

### Step 4 — Your base map is still there

The overlay is transparent apart from the roads themselves. Imagery, terrain and
labels stay readable underneath, which is the whole point — you are looking at
traffic *in context*, not at a traffic map.

![Traffic over satellite imagery](screenshots/4_over_imagery.png)

### Step 5 — Leave it alone

This is the part that matters. Put the device down and do not touch it. The
status line keeps updating on its own, and so does the traffic.

![Status line after several unattended refreshes](screenshots/5_status_refreshing.png)

The status line tells you three things:

- **that the overlay is on**, and which source is drawing;
- **how often it refreshes**;
- **when tiles last actually arrived** — not when the plugin last asked for
  them. If the network drops, this timestamp stops moving and a warning appears.
  That is the difference between "live" and "a picture of five minutes ago".

### Step 6 — Change how often it refreshes

Tap **Interval** and pick anything from 15 seconds to 10 minutes. 60 seconds is
the default and suits most driving; a longer interval is kinder to battery and
data on a long shift.

![Refresh interval choices](screenshots/6_interval_dialog.png)

### Step 7 — Refresh right now

Tap **Refresh now** if you want the current picture immediately rather than
waiting out the interval. The timestamp updates a few seconds later, once the
new tiles have actually arrived.

![Refresh now](screenshots/7_refresh_now.png)

### Step 8 — Turn it off

Tap **Turn Off Traffic Overlay**. The overlay is removed, the refresh stops, and
all network activity stops with it. Your base map is untouched.

![The pane, off again](screenshots/8_pane_off_again.png)

---

## 4. Screen off, and why nothing is wrong

When the device screen goes off, the overlay stops refreshing and **no tiles are
requested at all** — it costs no battery and no data while nobody is looking at
it.

The moment you wake the device it refreshes, typically within about three
seconds of unlocking, before you have finished reading the map. The status says
so plainly for the couple of minutes afterwards:

> *Refreshed on wake at 07:22:33*

So if you pick the device up and the status shows that line, what you are
looking at arrived after you woke it — not before you put it down.

![Refreshed on wake](screenshots/9_refreshed_on_wake.png)

---

## 5. Good to know

- **Traffic that does not change is not a fault.** At three in the morning the
  picture will look identical refresh after refresh. Watch the *timestamp*, not
  the colours, to know it is live.
- **"Last refresh" means tiles arrived**, not that the traffic picture changed.
  A successful refresh with no change looks the same as no change at all —
  except the timestamp moves.
- **Coverage is wherever the provider has data.** Motorways and major roads are
  well covered in populated areas; expect little or nothing on forest roads and
  in the back country.
- **It costs data.** A refresh fetches the tiles covering your screen. Zoomed
  out over a city at a 15-second interval is the expensive case; a longer
  interval or a tighter zoom costs less.
- **Zoom matters.** Traffic is drawn from about zoom 10 down to street level.
  Zoomed far out you will see motorways only.
- **One overlay at a time.** Turning it on replaces whatever the plugin was
  drawing before; it never stacks two.

---

## 6. Contact

Andreas Johansson, takwerx
_(repository issue tracker — added when published)_
