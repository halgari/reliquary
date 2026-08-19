#!/usr/bin/env python3
"""Build press/cover.png -- the Nexus Mods page image.

    python3 press/make-cover.py

1280x720 because that is Nexus's 16:9 slot; anything narrower is upscaled by
the site and anything taller is cropped. The source is the full-resolution
logo (1099x259), not resources/reliquary-logo.png -- that one is deliberately
downscaled to 330x78 for the 26px title bar and would be soft at this size.

Palette is Gilt, straight from the brand spec: #0C0C0C ground, #C2A35F gold,
#F2F0EE text. Surfaces stay flat; the only glow is the accent halo behind the
mark, which is the same treatment the title bar gives it.
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

W, H = 1280, 720
BG = (12, 12, 12)
GOLD = (194, 163, 95)
TEXT = (242, 240, 238)

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)

img = Image.new("RGB", (W, H), BG)

# the halo: one soft gold ellipse, blurred. Drawn on its own layer so the blur
# does not touch the logo's edges.
# Kept faint on purpose. At full strength it stopped reading as a glow behind
# the mark and started reading as a brown cast over the whole ground, which is
# exactly what "surfaces stay flat" is there to prevent.
halo = Image.new("L", (W, H), 0)
ImageDraw.Draw(halo).ellipse([W//2 - 330, H//2 - 210, W//2 + 330, H//2 + 60], fill=42)
halo = halo.filter(ImageFilter.GaussianBlur(130))
img = Image.composite(Image.new("RGB", (W, H), GOLD), img, halo.point(lambda v: int(v * 0.30)))

logo = Image.open(os.path.join(here, "reliquary-logo-full.png")).convert("RGBA")
target_w = 760
logo = logo.resize((target_w, round(logo.height * target_w / logo.width)), Image.LANCZOS)

# Centre the logo AND the tagline as one block rather than centring the logo
# and hanging text off it. The first cut offset the logo by a fixed 46px to
# leave room for a rule and a disclaimer line underneath; with those gone the
# same offset left the whole composition sitting high.
GAP, TAGLINE_H = 40, 38
block_h = logo.height + GAP + TAGLINE_H
ly = (H - block_h) // 2
lx = (W - logo.width) // 2
img.paste(logo, (lx, ly), logo)

d = ImageDraw.Draw(img)

def font(name, size):
    return ImageFont.truetype(os.path.join(root, "resources", "fonts", name), size)

def centre(text, y, f, fill, tracking=0):
    if tracking:
        widths = [d.textlength(ch, font=f) + tracking for ch in text]
        x = (W - (sum(widths) - tracking)) / 2
        for ch, w in zip(text, widths):
            d.text((x, y), ch, font=f, fill=fill)
            x += w
    else:
        d.text(((W - d.textlength(text, font=f)) / 2, y), text, font=f, fill=fill)

centre("Install any version of a Steam game", ly + logo.height + GAP,
       font("HankenGrotesk-Regular.ttf", 30), TEXT)

img.save(os.path.join(here, "cover.png"))
print("wrote", os.path.join(here, "cover.png"), img.size)
