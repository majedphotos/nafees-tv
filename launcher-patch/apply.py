#!/usr/bin/env python3
"""Adds the Noor line to the CleanYourTV launcher: Hijri date, weather and the next prayer under the clock,
the weather supplication (rain, thunder, wind), «جمعة مباركة» on Friday and «الأيام البيض» on the 13th to 15th,
and the newest-episode thumbnail on cards marked "latest" in sources.json. Also points the prayer-times addon at your city.

    python3 apply.py <cleanyourtv folder> <lat> <lon> <aladhan method> "<city name>"

Safe to run twice. Afterwards: build and install the launcher (launcher/build-and-install.sh) and the
prayer-times addon, then turn on shamsi_date in the theme (that is the line Noor writes into).
"""
import pathlib
import re
import sys

if len(sys.argv) != 6:
    sys.exit(__doc__)
root, lat, lon, method, city = pathlib.Path(sys.argv[1]), *sys.argv[2:]
float(lat), float(lon), int(method)  # fail early on a typo
here = pathlib.Path(__file__).resolve().parent
src = root / "launcher/app/src/main"


def patch(path, old, new, done_marker):
    text = path.read_text(encoding="utf-8")
    if done_marker in text:
        return
    if old not in text:
        sys.exit(f"Could not find the spot to change in {path} - the launcher changed upstream; patch it by hand.")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


noor = (here / "Noor.java").read_text(encoding="utf-8")
import json
cards = json.load(open(here.parent / "sources.json", encoding="utf-8"))["cards"]
live = ",".join(f"app.nafeestv.{c['id']}={c['list']}" for c in cards if c.get("latest") and c.get("list"))
noor = noor.replace("@LAT@", lat).replace("@LON@", lon).replace("@METHOD@", method).replace("@LIVE_CARDS@", live)
noor = noor.replace("@FRIDAY_FIRST@", ",".join(f"app.nafeestv.{c['id']}" for c in cards if c.get("friday_first")))
(src / "java/com/gathin/tvhome/Noor.java").write_text(noor, encoding="utf-8")

patch(src / "AndroidManifest.xml",
      '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
      '    <uses-permission android:name="android.permission.INTERNET" />\n'
      '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
      'android.permission.INTERNET')

patch(src / "java/com/gathin/tvhome/MainActivity.java",
      "        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));",
      "        String noorGreeting = Noor.greeting();\n"
      "        if (noorGreeting != null && festiveGreeting.isEmpty()) greeting.setText(noorGreeting);\n"
      "        if (shamsi) {\n"
      "            shamsiDate.setText(Noor.line());\n"
      "            shamsiDate.setTextColor(Noor.whiteDay() ? 0xFFFFE08A : 0xE6FFFFFF);\n"
      "        }",
      "Noor.line()")

# Friday: the Kahf card leads the dock (Noor.dockOrder).
patch(src / "java/com/gathin/tvhome/AppAdapter.java",
      "        Collections.sort(fresh, (a, b) -> {\n",
      "        final List<String> favourites = Noor.dockOrder(this.favourites);\n        Collections.sort(fresh, (a, b) -> {\n",
      "Noor.dockOrder")

# A live card's thumbnail arrived: redraw the tiles (checked with the clock, once a minute).
patch(src / "java/com/gathin/tvhome/MainActivity.java",
      "        String noorGreeting = Noor.greeting();\n",
      "        if (Noor.liveChanged()) reloadApps(false);\n        String noorGreeting = Noor.greeting();\n",
      "Noor.liveChanged")

patch(src / "java/com/gathin/tvhome/AppAdapter.java",
      "    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {\n",
      "    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {\n"
      "        android.graphics.Bitmap live = Noor.liveBanner(ri.activityInfo.packageName);\n"
      "        if (live != null) return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), live);\n",
      "Noor.liveBanner")

layout = src / "res/layout/activity_main.xml"
text = layout.read_text(encoding="utf-8")
m = re.search(r'android:id="@\+id/shamsiDate".*?/>', text, re.S)
if not m:
    sys.exit(f"Could not find shamsiDate in {layout}")
block = m.group(0)
if 'android:maxLines="2"' not in block:
    new = block.replace('android:singleLine="true"', 'android:maxLines="2"\n                            android:lineSpacingExtra="4dp"')
    layout.write_text(text.replace(block, new), encoding="utf-8")

# The prayer-times tile carries its name in Arabic and is drawn by Noor.prayerTile() (today's five times), so it
# is an ordinary app tile rather than a module card, whose label may only be Latin letters.
manifest = root / "addons/prayer-times/app/src/main/AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8").replace('android:label="Prayer Times"', 'android:label="أوقات الصلاة"')
text = re.sub(r'\s*<!-- Module card for the Clean Your TV launcher.*?<meta-data android:name="com.gathin.tvhome.card"[^>]*/>',
              "", text, flags=re.S)
manifest.write_text(text, encoding="utf-8")

cfg = root / "addons/prayer-times/app/src/main/res/values/config.xml"
text = cfg.read_text(encoding="utf-8")
for name, value in (("latitude", lat), ("longitude", lon), ("place", city), ("method", method)):
    text = re.sub(rf'(<string name="{name}"[^>]*>)[^<]*(</string>)', rf"\g<1>{value}\g<2>", text)
text = re.sub(r'(<bool name="reminders">)\w+(</bool>)', r"\g<1>true\g<2>", text)
cfg.write_text(text, encoding="utf-8")
print("Noor patch applied:", city, lat, lon, "method", method)
