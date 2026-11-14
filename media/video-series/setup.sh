#!/usr/bin/env bash
# One-time setup for the video-series pipeline:
#   - npm deps (Playwright 1.65 + Chromium, the OFL fonts)
#   - espeak-ng (Kokoro's Spanish G2P), via Homebrew when missing
#   - an isolated Python venv with the pinned TTS stack (requirements.txt) and the Kokoro weights,
#     from the official Hugging Face repo hexgrad/Kokoro-82M (pinned revision)
# Everything goes under $TOOLS (default: build/tools, git-ignored).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$here/build/tools}"
PY="${PYTHON:-python3.12}"
KOKORO_REV="${KOKORO_REV:-f3ff3571791e39611d31c381e3a41a3af07b4987}"   # hexgrad/Kokoro-82M, v1.0 weights
mkdir -p "$TOOLS"

cd "$here"
[ -d node_modules/playwright ] || npm ci
npx playwright install chromium >/dev/null

command -v espeak-ng >/dev/null || brew install espeak-ng

if [ ! -x "$TOOLS/venv/bin/python" ]; then
  "$PY" -m venv "$TOOLS/venv"
  "$TOOLS/venv/bin/python" -I -m pip install -q --upgrade pip==25.2
  "$TOOLS/venv/bin/python" -I -m pip install -q -r "$here/requirements.txt"
fi

# The weights and the two voices of the series, from the official repo, at a pinned revision.
HF_HOME="$TOOLS/hf" "$TOOLS/venv/bin/python" -I -c "
from huggingface_hub import hf_hub_download
for f in ['config.json', 'kokoro-v1_0.pth', 'voices/af_heart.pt', 'voices/ef_dora.pt']:
    print(hf_hub_download('hexgrad/Kokoro-82M', f, revision='$KOKORO_REV'))
"
echo "setup done: $TOOLS"
