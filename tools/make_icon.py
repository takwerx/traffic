#!/usr/bin/env python3
"""Derive the shipped plugin icon from the source artwork.

The artwork in docs/icon-source/ is the record; this script is how it becomes the
two PNGs the plugin and the manual actually use. Re-run it after replacing the
source; do not hand-edit the outputs.

Two things it fixes, both measured rather than assumed:

**The halo.** The exported artwork carried a soft glow -- about 11% of the canvas
sat at alpha 1-63, and only 1% of the ink was ever fully opaque. Downscaled, that
haze averages into grey and the icon reads as a smudge instead of white lines.
The alpha is remapped so near-solid ink becomes solid and the halo goes, while
genuine antialiasing on the edges is preserved.

**Aspect.** The source is not square. It is centred on a square canvas before
resizing, so nothing is stretched.

ATAK tints toolbar icons, so the output is pure white on full transparency:
colour in the file would be discarded, and a filled background renders as a
solid block. For reference, ATAK's own toolbar icons measure 14-26 px of stroke
weight on a 256 canvas -- an icon much thinner than that disappears beside them.
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "..", "docs", "icon-source",
                      "traffic_plugin_icon.png")
ICON = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable",
                    "ic_launcher.png")
MANUAL = os.path.join(HERE, "..", "docs", "user_manual", "plugin_icon.png")

# Below HAZE is glow and is discarded; at or above SOLID is ink and is driven to
# fully opaque. Between them is real edge antialiasing and is rescaled.
HAZE = 64
SOLID = 190


def clean(img):
    alpha = img.split()[3].point(
        lambda v: 0 if v < HAZE else
        (255 if v >= SOLID else int(255 * (v - HAZE) / (SOLID - HAZE))))
    out = Image.new("RGBA", img.size, (255, 255, 255, 255))
    out.putalpha(alpha)
    return out


def main():
    src = os.path.normpath(SOURCE)
    if not os.path.isfile(src):
        print("no source artwork at %s" % src, file=sys.stderr)
        return 1

    img = clean(Image.open(src).convert("RGBA"))

    side = max(img.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.alpha_composite(img, ((side - img.width) // 2,
                                 (side - img.height) // 2))

    icon = os.path.normpath(ICON)
    square.resize((256, 256), Image.LANCZOS).save(icon)
    print("wrote %s (256x256)" % icon)

    manual = os.path.normpath(MANUAL)
    if os.path.isdir(os.path.dirname(manual)):
        square.resize((128, 128), Image.LANCZOS).save(manual)
        print("wrote %s (128x128)" % manual)

    hist = Image.open(icon).split()[3].histogram()
    ink = sum(hist[1:])
    print("ink %.0f%% fully opaque, %.0f%% of the canvas transparent"
          % (100.0 * hist[255] / ink, 100.0 * hist[0] / sum(hist)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
