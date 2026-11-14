#!/usr/bin/env bash
# Renders one chapter master:  ./render.sh <NN> <en|es>      e.g. ./render.sh 04 es
#   1 tts        narration of both languages → build/chNN/<lang>/audio (cached per sentence)
#   2 timeline   this master's scene lengths = its own narration (+ padding)
#   3 record     the app, fresh; the real console filmed frame by frame; real terminal output
#   4 render     HTML scenes + recorded frames → silent video
#   5 audio/srt  narration mix; .srt in EN and ES timed to this master
#   6 assemble   → $OUT/chNN-<lang>.mp4, chNN-<lang>.srt, chNN-<lang>.<other>.srt, chNN-thumb.png, chNN-youtube.md
# Env: OUT (default build/out), SKIP_RECORD=1 (reuse frames), ONLY=scene,… (record some scenes),
#      TOOLS (setup.sh's dir), MAVEN_ARGS (passed to Maven), PORT (8095).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; cd "$here"
ch="${1:?chapter, e.g. 04}"; lang="${2:?en or es}"
TOOLS="${TOOLS:-$here/build/tools}"; OUT="${OUT:-$here/build/out}"
PYV="$TOOLS/venv/bin/python"; export HF_HOME="${HF_HOME:-$TOOLS/hf}"
[ -x "$PYV" ] || { echo "run ./setup.sh first"; exit 1; }
b="build/ch$ch/$lang"; mkdir -p "$b" "$OUT"
app=$(node -e "console.log(JSON.parse(require('fs').readFileSync('chapters/$ch/script.json','utf8')).app)")
# Optional in script.json: "port" (the app's own port), "appArgs" (Spring arguments), "before" (a shell command run
# at the repo root before the app starts), "apps" ([{name, app, port, appArgs}]: more apps, each exported to the
# recorder as ENGINE_<NAME>=http://localhost:<port>).
cfg() { node -e "const s=JSON.parse(require('fs').readFileSync('chapters/$ch/script.json','utf8')); const v=($1); if (v!=null) console.log(v)"; }
port=$(cfg s.port); [ -n "$port" ] && [ -z "${PORT:-}" ] && export PORT="$port"
[ -n "${PORT:-}" ] && export ENGINE="${ENGINE:-http://localhost:$PORT}"

echo "== 1 tts"
for l in en es; do "$PYV" -I scripts/tts.py "chapters/$ch/script.json" "$l" "build/ch$ch/$l/audio" 2>&1 | grep -vE 'Warning|warn|^\s*$|WeightNorm|dropout' || true; done
echo "== 2 timeline"
for l in en es; do node scripts/timeline.mjs "chapters/$ch/script.json" "build/ch$ch/$l/audio/timing.json" "build/ch$ch/$l/timeline.json"; done
echo "== 3 record"
if [ -z "${SKIP_RECORD:-}" ]; then
  extra=$(cfg "(s.apps||[]).map(a=>[a.name,a.app,a.port,a.appArgs||''].join('|')).join('\\n')||null")
  stop_all() { scripts/app.sh stop >/dev/null 2>&1 || true; while IFS='|' read -r n a p x; do [ -n "$n" ] && APP_NAME="$n" PORT="$p" scripts/app.sh stop >/dev/null 2>&1 || true; done <<< "$extra"; }
  trap stop_all EXIT
  before=$(cfg s.before); [ -n "$before" ] && (cd ../.. && bash -c "$before")
  APP_ARGS="$(cfg s.appArgs | sed "s#{here}#$here#g")" scripts/app.sh start "$app"   # {here}: this directory
  while IFS='|' read -r n a p x; do
    [ -z "$n" ] && continue
    APP_NAME="$n" PORT="$p" APP_ARGS="$x" scripts/app.sh start "$a"
    export "ENGINE_$(echo "$n" | tr '[:lower:]-' '[:upper:]_')=http://localhost:$p"
  done <<< "$extra"
  node scripts/record-live.mjs "$ch" "$lang"
  stop_all; trap - EXIT
fi
echo "== 4 render"
node scripts/render-video.mjs "$ch" "$lang"
echo "== 5 audio + subtitles"
"$PYV" -I scripts/mix.py "$b/timeline.json" "build/ch$ch/$lang/audio" "$b/narration.wav"
node scripts/srt.mjs "$ch" "$lang"
echo "== 6 assemble"
scripts/assemble.sh "$ch" "$lang" "$OUT"
node scripts/render-video.mjs "$ch" en --thumb >/dev/null && ffmpeg -y -loglevel error -i "build/ch$ch/thumb-en.png" -vf scale=1280:720 "$OUT/ch$ch-thumb.png"
node scripts/youtube.mjs "$ch" "$OUT"
echo "done: $OUT/ch$ch-$lang.mp4"
