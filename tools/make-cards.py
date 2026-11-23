#!/usr/bin/env python3
"""Draws every card's banner (nafees-app/app/src/<id>/res/drawable-xhdpi/banner.png and icon.png, 320x180) from
sources.json with headless Chrome and the Cairo font. A card with "thumb": true gets its YouTube thumbnail instead
(640x360). Usage: tools/make-cards.py"""
import io, json, os, subprocess, tempfile, urllib.request
from PIL import Image

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
cards = json.load(open(f"{root}/sources.json", encoding="utf8"))["cards"]
COLOURS = {"alifba": ("#d8b25a", "#7a5a18", "#1b1204"), "kahf": ("#1f7a55", "#0b3a28", "#fff"),
           "salawat": ("#2f8f7d", "#0e4a40", "#fff"), "baqara": ("#8a6a2a", "#3f2d0c", "#fff"),
           "sabah": ("#c9772a", "#6e3510", "#fff"), "masa": ("#3a3f9a", "#161a4f", "#fff"),
           "ruqya": ("#1e6b73", "#0b363b", "#fff"), "anasheed": ("#7b3f6e", "#371a32", "#fff"),
           "tarteel": ("#3e9b4f", "#14451f", "#fff"), "amma": ("#2c6e8f", "#0f3347", "#fff"),
           "tabarak": ("#9a4a3a", "#45190f", "#fff")}
SUB = {"alifba": "برنامج الشيخ أحمد النفيس", "tarteel": "مع الشيخ أحمد النفيس", "amma": "عثمان الحداد", "tabarak": "عثمان الحداد"}
SIZE = {"salawat": 56}
font = os.path.expanduser("~/Library/Fonts/Cairo.ttf")
html = f"""<html><head><meta charset=utf-8><style>
@font-face{{font-family:C;src:url(file://{font})}} body{{margin:0;background:#000}}
.c{{width:640px;height:360px;position:relative;overflow:hidden;font-family:C;direction:rtl;display:flex;flex-direction:column;justify-content:center;padding:0 52px;box-sizing:border-box}}
.c::before{{content:"";position:absolute;inset:0;background:url(file://{root}/themes/zakhrafa/wallpaper.jpg) 0 0/1100px;opacity:.10;mix-blend-mode:screen}}
.t{{font-weight:800;line-height:1.3;position:relative;white-space:nowrap}} .s{{font-weight:600;font-size:34px;opacity:.85;position:relative}}
</style></head><body>"""
for c in cards:
    a, b, fg = COLOURS.get(c["id"], ("#1f7a55", "#0b3a28", "#fff"))
    size = SIZE.get(c["id"], 84 if len(c["name"]) <= 11 else 66)
    html += (f'<div class=c style="background:radial-gradient(120% 140% at 85% 10%,{a},{b});color:{fg}">'
             f'<div class=t style="font-size:{size}px">{c["name"]}</div><div class=s>{SUB.get(c["id"], "أحمد النفيس")}</div></div>')
tmp = tempfile.mkdtemp()
open(f"{tmp}/cards.html", "w", encoding="utf8").write(html + "</body></html>")
subprocess.run(["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "--headless=new", "--disable-gpu",
                "--allow-file-access-from-files", "--hide-scrollbars", f"--window-size=640,{360 * len(cards)}",
                f"--screenshot={tmp}/cards.png", f"file://{tmp}/cards.html"], check=True, capture_output=True)
sheet = Image.open(f"{tmp}/cards.png")
for i, c in enumerate(cards):
    out = f"{root}/nafees-app/app/src/{c['id']}/res/drawable-xhdpi"
    os.makedirs(out, exist_ok=True)
    tile = sheet.crop((0, 360 * i, 640, 360 * (i + 1))).resize((320, 180), Image.LANCZOS).convert("RGB")
    if c.get("thumb"):
        for size in ("maxresdefault", "sddefault", "hqdefault"):
            try:
                raw = urllib.request.urlopen(f"https://i.ytimg.com/vi/{c['video']}/{size}.jpg", timeout=15).read()
            except Exception:
                continue
            im = Image.open(io.BytesIO(raw)).convert("RGB")
            w, h = im.size  # sd/hq are 4:3 with black bars: keep the 16:9 middle
            ch = round(w * 9 / 16)
            tile = im.crop((0, (h - ch) // 2, w, (h - ch) // 2 + ch)).resize((640, 360), Image.LANCZOS)
            break
        else:
            print("no thumbnail for", c["id"])
    for n in ("banner", "icon"):
        tile.save(f"{out}/{n}.png")
strip = Image.new("RGB", (320, 180 * len(cards)))
for i, c in enumerate(cards):
    strip.paste(Image.open(f"{root}/nafees-app/app/src/{c['id']}/res/drawable-xhdpi/banner.png").resize((320, 180)), (0, 180 * i))
strip.save(f"{root}/design/cards-small.png")
print(len(cards), "cards")
