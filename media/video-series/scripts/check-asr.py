"""Self-check: transcribe each narrated sentence back with Whisper and compare it with the script, so
a mispronounced or garbled line shows up without listening to the whole master.

    <asr venv>/bin/python -I scripts/check-asr.py <audio dir with timing.json> <lang> [model]

Prints the sentences whose word match is under 80 %. Optional (setup: pip install faster-whisper==1.2.0).
"""
import json, re, sys, unicodedata, wave
import numpy as np
from difflib import SequenceMatcher
from faster_whisper import WhisperModel

d, lang = sys.argv[1], sys.argv[2]
model = WhisperModel(sys.argv[3] if len(sys.argv) > 3 else "small", device="cpu", compute_type="int8")
norm = lambda s: re.sub(r"[^a-z0-9ñ ]", " ", unicodedata.normalize("NFKD", s.lower()).encode("ascii", "ignore").decode()).split()
timing = json.load(open(f"{d}/timing.json", encoding="utf-8"))
scores = []
for sc in timing["scenes"]:
    for s in sc["sentences"]:
        with wave.open(f"{d}/{s['file']}") as w:
            a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
            sr = w.getframerate()
        a = np.interp(np.arange(0, len(a), sr / 16000), np.arange(len(a)), a).astype(np.float32)  # → 16 kHz
        segs, _ = model.transcribe(a, language=lang, beam_size=5)
        heard = " ".join(x.text for x in segs).strip()
        r = SequenceMatcher(None, norm(s["text"]), norm(heard)).ratio()
        scores.append(r)
        if r < 0.8:
            print(f"[{r:.2f}] {sc['id']}: {s['text']}\n       heard: {heard}")
print(f"{lang}: {len(scores)} sentences, mean word match {sum(scores) / len(scores):.2f}, under 0.8: {sum(r < 0.8 for r in scores)}")
