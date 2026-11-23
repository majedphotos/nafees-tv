#!/usr/bin/env python3
"""Builds themes/nafees/wallpaper.jpg: the green-and-gold Islamic pattern with the sheikh's cut-out photo on the right.
The photo's pixels are pasted as they are (only its background is removed, never the face or features).
Usage: tools/make-nafees-wallpaper.py <cutout.png> [--mirror] [--left] [--out file.jpg]   (made with rembg from a photo in صور-الشيخ/)"""
import os, sys
from PIL import Image, ImageFilter, ImageDraw

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W, H, TILE = 1920, 1080, 132  # the pattern repeats every 132 px
src = Image.open(f"{root}/themes/zakhrafa/wallpaper.jpg").convert("RGB")
import numpy as np
from PIL import ImageFilter as F
# Split the pattern into its fine detail and its soft lighting, so pieces can be moved without seams.
blur = lambda im: np.asarray(im.filter(F.GaussianBlur(90)), float)
light = blur(src)
detail = np.asarray(src, float) - light
# Cover the big medallion on the right with more of the plain pattern, shifted by whole tiles so it lines up.
detail[:, 960:1620] = detail[:, 300:960]
detail[:, 1620:] = detail[:, 300:600]
light[:, 960:] = light[:, 959::-1][:, :960]  # the left half's glow, mirrored
bg = Image.fromarray(np.clip(detail + light, 0, 255).astype("uint8"))
glow = Image.new("L", (W, H), 0)
gx = 430 if "--left" in sys.argv else 1500
ImageDraw.Draw(glow).ellipse((gx - 350, 150, gx + 350, 850), fill=110)
bg = Image.composite(Image.new("RGB", (W, H), (214, 170, 90)), bg, glow.filter(ImageFilter.GaussianBlur(160)))

s = Image.open(sys.argv[1])
s = s.crop(s.getchannel("A").getbbox())
if "--mirror" in sys.argv:
    s = s.transpose(Image.FLIP_LEFT_RIGHT)  # a flip only, as Majed asked: nothing on the face is redrawn
h = 880
s = s.resize((round(s.width * h / s.height), h), Image.LANCZOS)  # scale only
x, y = (430 if "--left" in sys.argv else 1500) - s.width // 2, 250
shadow = Image.new("L", (W, H), 0)
shadow.paste(s.getchannel("A"), (x + 14, y + 10))
shadow = shadow.filter(ImageFilter.GaussianBlur(22)).point(lambda v: int(v * 0.55))
bg = Image.composite(Image.new("RGB", (W, H)), bg, shadow)
bg.paste(s, (x, y), s)
fade = Image.linear_gradient("L").resize((W, H)).point(lambda v: min(255, int(max(0, v - 150) * 1.4)))
bg = Image.composite(Image.new("RGB", (W, H), (2, 14, 9)), bg, fade)
os.makedirs(f"{root}/themes/nafees", exist_ok=True)
out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else f"{root}/themes/nafees/wallpaper.jpg"
bg.save(out, quality=92)
print(out)
