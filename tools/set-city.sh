#!/bin/bash
# Points everything at one city, so the adhan, the prayer-times tile and the line under the clock agree:
# the Nafees cards app (gradle.properties), the launcher line and the prayer-times add-on (launcher-patch/apply.py).
# Usage: tools/set-city.sh <lat> <lon> <aladhan-method> "<city name>"
#   method: 4 Umm al-Qura (Saudi), 9 Kuwait, 8 Gulf, 16 Dubai, 10 Qatar, 5 Egypt, 3 Muslim World League
set -e
[ $# -eq 4 ] || { sed -n 2,5p "$0"; exit 1; }
cd "$(dirname "$0")/.."
python3 - "$@" <<'PY'
import re, sys
lat, lon, method = float(sys.argv[1]), float(sys.argv[2]), int(sys.argv[3])
p = "nafees-app/gradle.properties"
t = open(p, encoding="utf8").read()
for k, v in (("lat", lat), ("lon", lon), ("method", method)):
    t = re.sub(rf"^nafees\.{k}=.*$", f"nafees.{k}={v}", t, flags=re.M)
open(p, "w", encoding="utf8").write(t)
PY
python3 launcher-patch/apply.py ~/cleanyourtv "$1" "$2" "$3" "$4"
grep '^nafees\.' nafees-app/gradle.properties
