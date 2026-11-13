#!/usr/bin/env bash
# Encodes the rendered master (build/master.mp4, from render-animated.mjs) into the deliverables:
#   build/eventconductor-promo-1080p.mp4      YouTube upload: H.264 1080p30, CRF 18, faststart
#   doc/public/video/eventconductor-promo.webm  the site: VP9 1080p, two-pass, ~0.75 Mbps
#   doc/public/video/eventconductor-promo.mp4   the site's fallback: H.264 1080p, ~0.8 Mbps
#   doc/public/video/eventconductor-promo-poster.jpg  the frame shown before play
# No audio track: the video is made to be watched muted (captions are on screen).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(cd "$here/../.." && pwd)"
master="$here/build/master.mp4"
site="$repo/doc/public/video"
mkdir -p "$site"
[ -f "$master" ] || { echo "no $master — run: node scripts/render-animated.mjs"; exit 1; }

# YouTube: high quality; YouTube re-encodes anyway, so give it a clean source.
ffmpeg -y -loglevel error -i "$master" -c:v libx264 -preset slow -crf 18 -pix_fmt yuv420p \
  -profile:v high -level 4.2 -r 30 -movflags +faststart -an "$here/build/eventconductor-promo-1080p.mp4"

# Site, WebM (VP9, two-pass): most of the video is still text and UI, which VP9 holds sharp at
# low bitrates; the bitrate keeps the file well under 10 MB.
ffmpeg -y -loglevel error -i "$master" -c:v libvpx-vp9 -b:v 720k -minrate 300k -maxrate 1600k -row-mt 1 \
  -tile-columns 2 -pix_fmt yuv420p -pass 1 -passlogfile "$here/build/vp9" -an -f null /dev/null
ffmpeg -y -loglevel error -i "$master" -c:v libvpx-vp9 -b:v 720k -minrate 300k -maxrate 1600k -row-mt 1 \
  -tile-columns 2 -pix_fmt yuv420p -pass 2 -passlogfile "$here/build/vp9" -an "$site/eventconductor-promo.webm"

# Site, MP4 fallback (Safari without VP9, older devices).
ffmpeg -y -loglevel error -i "$master" -c:v libx264 -preset slow -b:v 800k -maxrate 1800k -bufsize 3600k \
  -pass 1 -passlogfile "$here/build/x264" -pix_fmt yuv420p -an -f null /dev/null
ffmpeg -y -loglevel error -i "$master" -c:v libx264 -preset slow -b:v 800k -maxrate 1800k -bufsize 3600k \
  -pass 2 -passlogfile "$here/build/x264" -pix_fmt yuv420p -movflags +faststart -an "$site/eventconductor-promo.mp4"

# Poster: the quote over the green iceberg (t = 22 s).
ffmpeg -y -loglevel error -ss 22 -i "$master" -frames:v 1 -q:v 3 "$site/eventconductor-promo-poster.jpg"

ls -la "$here/build/eventconductor-promo-1080p.mp4" "$site"
