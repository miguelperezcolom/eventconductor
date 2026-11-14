"""Lays each narration sentence at its place on the master's timeline → one WAV (24 kHz mono).

    python -I scripts/mix.py <timeline.json> <audio_dir> <out.wav>
"""
import json, sys
import numpy as np
import soundfile as sf

tl = json.load(open(sys.argv[1], encoding="utf-8"))
audio_dir, out = sys.argv[2], sys.argv[3]
SR = 24000
track = np.zeros(int((tl["duration"] + 0.5) * SR), dtype=np.float32)
for sc in tl["scenes"]:
    for cue in sc["cues"]:
        a, sr = sf.read(f"{audio_dir}/{cue['file']}", dtype="float32")
        assert sr == SR, cue["file"]
        i = int(round((sc["start"] + cue["start"]) * SR))
        track[i:i + len(a)] += a[: len(track) - i]
track = track[: int(tl["duration"] * SR)]
peak = float(np.max(np.abs(track))) or 1.0
track *= min(1.0, 0.89 / peak)                 # about -1 dBFS peak, no clipping
sf.write(out, track, SR, subtype="PCM_16")
print(f"mix {out}: {len(track) / SR:.2f}s, peak {peak:.2f}")
