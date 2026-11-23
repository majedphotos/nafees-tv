#!/bin/bash
# Builds the Nafees TV cards (the list in sources.json) and installs them on the TV.
# The Kahf app also carries the reminders, the adhan and the phone message page, so it gets the overlay
# and usage-access permissions and is started once.
# Usage: tools/install-nafees.sh <tv-ip>
set -e
D="$1:5555"; [ -n "$1" ] || { echo "usage: $0 <tv-ip>"; exit 1; }
cd "$(dirname "$0")/../nafees-app"
./gradlew -q assembleRelease
for f in $(python3 -c 'import json;print(" ".join(c["id"] for c in json.load(open("../sources.json"))["cards"]))'); do
  adb -s "$D" install -r "app/build/outputs/apk/$f/release/app-$f-release.apk" >/dev/null && echo "✅ $f"
done
adb -s "$D" shell appops set app.nafeestv.kahf SYSTEM_ALERT_WINDOW allow
adb -s "$D" shell appops set app.nafeestv.kahf GET_USAGE_STATS allow
sleep 8 # Android restarts the app right after an update, so start it once that has settled
adb -s "$D" shell am broadcast -n app.nafeestv.kahf/app.nafeestv.Reminder >/dev/null
echo "Phone page: http://$1:8080"
