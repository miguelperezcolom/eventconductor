#!/usr/bin/env bash
# Muxes a master: the silent render (video.mp4) + the narration mix (narration.wav) →
# H.264 high, yuv420p, CRF 18, 30 fps, faststart, AAC 48 kHz. NO music (rule 3).
#   scripts/assemble.sh <NN> <lang> <out_dir>   → <out_dir>/chNN-<lang>.mp4 (+ the two .srt)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
ch="$1"; lang="$2"; out="$3"; b="$here/build/ch$ch/$lang"
mkdir -p "$out"
ffmpeg -y -loglevel error -i "$b/video.mp4" -i "$b/narration.wav" -map 0:v -map 1:a \
  -c:v libx264 -preset slow -crf 18 -pix_fmt yuv420p -profile:v high -level 4.2 -r 30 \
  -c:a aac -b:a 192k -ar 48000 -ac 2 -shortest -movflags +faststart \
  -metadata:s:a:0 language="$([ "$lang" = es ] && echo spa || echo eng)" "$out/ch$ch-$lang.mp4"
# The master's own-language track is chNN-<lang>.srt; the other language, timed to this master:
other=$([ "$lang" = en ] && echo es || echo en)
cp "$b/ch$ch-$lang.$lang.srt" "$out/ch$ch-$lang.srt"
cp "$b/ch$ch-$lang.$other.srt" "$out/ch$ch-$lang.$other.srt"
v=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out/ch$ch-$lang.mp4")
a=$(ffprobe -v error -select_streams a -show_entries stream=duration -of csv=p=0 "$out/ch$ch-$lang.mp4")
echo "master $out/ch$ch-$lang.mp4  video ${v}s  audio ${a}s"
