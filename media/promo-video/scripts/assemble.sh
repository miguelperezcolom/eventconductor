#!/usr/bin/env bash
# Encodes the rendered master (build/master.mp4, from render-animated.mjs) for YouTube:
#   build/eventconductor-promo-1080p.mp4   H.264 1080p30, CRF 18, faststart, no audio track
#   build/eventconductor-promo-thumbnail.jpg  1280x720 thumbnail (the quote over the green iceberg)
# The video is published on YouTube and embedded on the home page; nothing here goes in the repo.
# OUT=<dir> also copies both files there.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
master="$here/build/master.mp4"
[ -f "$master" ] || { echo "no $master — run: node scripts/render-animated.mjs"; exit 1; }

ffmpeg -y -loglevel error -i "$master" -c:v libx264 -preset slow -crf 18 -pix_fmt yuv420p \
  -profile:v high -level 4.2 -r 30 -movflags +faststart -an "$here/build/eventconductor-promo-1080p.mp4"
ffmpeg -y -loglevel error -ss 22 -i "$master" -frames:v 1 -vf scale=1280:720 -q:v 2 "$here/build/eventconductor-promo-thumbnail.jpg"

if [ -n "${OUT:-}" ]; then mkdir -p "$OUT"; cp "$here/build/eventconductor-promo-1080p.mp4" "$here/build/eventconductor-promo-thumbnail.jpg" "$OUT/"; fi
ls -la "$here/build/eventconductor-promo-1080p.mp4" "$here/build/eventconductor-promo-thumbnail.jpg"
