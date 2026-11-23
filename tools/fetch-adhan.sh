#!/bin/bash
# Downloads the adhan you chose (Sheikh Ahmad Al-Nufais's, from sources.json) into the Kahf app,
# trimmed and at a gentle loudness. Without it the app still shows the prayer banner, just silently.
# Usage: tools/fetch-adhan.sh [youtube-url]   (default: the adhan in sources.json, Sheikh Ahmad Al-Nufais)
set -e
cd "$(dirname "$0")/.."
URL="${1:-https://www.youtube.com/watch?v=$(python3 -c 'import json;print(json.load(open("sources.json"))["adhan"]["video"])')}"
TMP=$(mktemp -d)
yt-dlp -q -x --audio-format mp3 -o "$TMP/a.%(ext)s" "$URL"
mkdir -p nafees-app/app/src/kahf/res/raw
ffmpeg -v error -y -i "$TMP/a.mp3" -af "silenceremove=start_periods=1:start_threshold=-45dB,loudnorm=I=-18:TP=-2" \
  -ar 44100 -ac 1 -b:a 96k nafees-app/app/src/kahf/res/raw/adhan.mp3
rm -rf "$TMP"
echo "adhan: $(ffprobe -v error -show_entries format=duration -of csv=p=0 nafees-app/app/src/kahf/res/raw/adhan.mp3 | cut -d. -f1) s"
