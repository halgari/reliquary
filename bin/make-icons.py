#!/usr/bin/env python3
# Reliquary — Copyright (C) 2026 Timothy Baldridge
# Licensed under the GNU General Public License v3 or later. See LICENSE.
"""Derive the application icon from the header logo.

resources/reliquary-logo.png is a 330x78 WORDMARK: the chest emblem, a gap, then
"RELIQUARY" in gold. An application icon has to be square and is rendered as
small as 16px, where the word is illegible anyway -- so the icon is the emblem
alone, cropped out of that same artwork so the two can never drift apart.

The crop is measured, not hardcoded to eyeballed pixels: the emblem is the first
run of fully-transparent-free columns in the alpha channel, and the gap before
the wordmark is what ends it. Re-run this after any change to the logo.

    python3 bin/make-icons.py

Writes, both committed so neither CI nor a packaging run needs Pillow:

    resources/reliquary-icon.png   256x256, for the JavaFX stage at runtime
                                   (taskbar, alt-tab, window icon)
    resources/reliquary.ico        multi-size, for jpackage --icon (the .exe)
"""
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: pip install --user Pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "resources", "reliquary-logo.png")

# Windows renders .ico entries from 16 up to 256; supplying each size explicitly
# beats letting the shell rescale one bitmap, which is what produces the mushy
# icons you see in small-icon list views.
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
ALPHA_FLOOR = 16  # below this a pixel is background, not artwork


def opaque_column_runs(alpha, w, h):
    """Contiguous column ranges that contain any artwork."""
    runs, start = [], None
    for x in range(w):
        used = any(alpha.getpixel((x, y)) > ALPHA_FLOOR for y in range(h))
        if used and start is None:
            start = x
        elif not used and start is not None:
            runs.append((start, x - 1))
            start = None
    if start is not None:
        runs.append((start, w - 1))
    return runs


def emblem(logo):
    """The emblem, cropped to its own bounds and centred on a square canvas."""
    w, h = logo.size
    alpha = logo.split()[3]
    runs = opaque_column_runs(alpha, w, h)
    if not runs:
        sys.exit(f"{SRC} has no opaque pixels")
    x0, x1 = runs[0]
    rows = [y for y in range(h)
            if any(alpha.getpixel((x, y)) > ALPHA_FLOOR for x in range(x0, x1 + 1))]
    y0, y1 = rows[0], rows[-1]
    mark = logo.crop((x0, y0, x1 + 1, y1 + 1))

    # Square it by padding rather than stretching -- the chest is not square and
    # scaling it to fit would visibly distort it. A little breathing room keeps
    # the artwork off the very edge, where Windows crops icons in some views.
    mw, mh = mark.size
    side = int(max(mw, mh) * 1.10)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(mark, ((side - mw) // 2, (side - mh) // 2), mark)
    return canvas, (x0, y0, x1, y1)


def main():
    logo = Image.open(SRC).convert("RGBA")
    square, box = emblem(logo)
    print(f"logo {logo.size}  emblem bbox x {box[0]}..{box[2]} y {box[1]}..{box[3]}"
          f"  square {square.size}")

    # One 256px master, and every other size a downscale of it. Pillow's ICO
    # writer silently DROPS any requested size larger than the image it is given,
    # so handing it the ~93px crop yielded an .ico that stopped at 64 and left
    # Windows to blow that up for large-icon views. Upscaling the emblem once,
    # here, is the lesser evil: the source artwork is only 85px wide, so 256 is
    # soft either way, but at least it is soft under our control.
    master = square.resize((256, 256), Image.LANCZOS)

    png = os.path.join(ROOT, "resources", "reliquary-icon.png")
    master.save(png)
    print(f"wrote {os.path.relpath(png, ROOT)}")

    ico = os.path.join(ROOT, "resources", "reliquary.ico")
    master.save(ico, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
    written = sorted(Image.open(ico).ico.sizes())
    if len(written) != len(ICO_SIZES):
        sys.exit(f"expected {ICO_SIZES} in the .ico, got {written}")
    print(f"wrote {os.path.relpath(ico, ROOT)}  sizes {[s for s, _ in written]}")


if __name__ == "__main__":
    main()
