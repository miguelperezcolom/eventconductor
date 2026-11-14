#!/usr/bin/env bash
# Self-check: stills from a finished master at the given times (or 2 per scene from its timeline),
# tiled 2x2 into review sheets → build/chNN/<lang>/check/sheet-*.png
#   scripts/contact-sheet.sh <NN> <lang> <mp4> [t1 t2 …]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"; ch="$1"; lang="$2"; mp4="$3"; shift 3
d="$here/build/ch$ch/$lang/check"; rm -rf "$d"; mkdir -p "$d"
if [ $# -eq 0 ]; then
  set -- $(node -e "const t=require('$here/build/ch$ch/$lang/timeline.json'); console.log(t.scenes.flatMap(s=>[s.start+Math.min(1.2,s.dur/4), s.start+s.dur*0.75]).map(x=>x.toFixed(2)).join(' '))")
fi
i=0; for t in "$@"; do ffmpeg -y -loglevel error -ss "$t" -i "$mp4" -frames:v 1 "$d/$(printf %03d $i)-t$t.png"; i=$((i+1)); done
ffmpeg -y -loglevel error -pattern_type glob -i "$d/[0-9]*.png" -vf "scale=960:540,tile=2x2" "$d/sheet-%02d.png"
ls "$d"/sheet-*.png
