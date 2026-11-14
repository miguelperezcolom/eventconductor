"""Narration → speech, one WAV per sentence, with Kokoro-82M (local, offline once set up).

    python -I scripts/tts.py <chapters/NN/script.json> <lang> <out_dir>

Writes <out_dir>/<scene>/<i>.wav (24 kHz mono) and <out_dir>/timing.json:
    {"lang", "voice", "sampleRate", "scenes": [{"id", "sentences": [{"text", "file", "duration"}]}]}
A sentence is synthesised again only when its text, voice or speed changed (cache key in the file name),
so editing one line of a script re-voices one line.

The voices are fixed for the whole series (rule 4): EN af_heart, ES ef_dora — both female, both
from the official repo hexgrad/Kokoro-82M at a pinned revision (setup.sh downloads them).
Narration entries are strings, or {"text": ..., "say": ...} when the ear needs another spelling
than the subtitle ("101" said as "one oh one"); subtitles always use "text".
"""
import hashlib, json, os, re, sys
from pathlib import Path

import numpy as np
import soundfile as sf

VOICES = {"en": ("a", "af_heart"), "es": ("e", "ef_dora")}
SPEED = {"en": 1.0, "es": 1.0}
REPO = "hexgrad/Kokoro-82M"
REV = os.environ.get("KOKORO_REV", "f3ff3571791e39611d31c381e3a41a3af07b4987")
SR = 24000


def sentences(entry):
    """A narration paragraph → sentences (each one subtitle cue and one WAV)."""
    if isinstance(entry, dict):
        return [entry]
    parts = re.split(r"(?<=[.!?…])\s+(?=[¿¡A-ZÁÉÍÓÚÑ«\"'])", entry.strip())
    return [{"text": p} for p in parts if p]


def main(script_path, lang, out_dir):
    script = json.loads(Path(script_path).read_text(encoding="utf-8"))
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    lang_code, voice = VOICES[lang]
    speed = SPEED[lang]

    pipe = None
    def synth(text):
        nonlocal pipe
        if pipe is None:
            from huggingface_hub import hf_hub_download
            from kokoro import KModel, KPipeline
            dl = lambda f: hf_hub_download(REPO, f, revision=REV)
            model = KModel(repo_id=REPO, config=dl("config.json"), model=dl("kokoro-v1_0.pth")).eval()
            pipe = (KPipeline(lang_code=lang_code, repo_id=REPO, model=model), dl(f"voices/{voice}.pt"))
        p, vpath = pipe
        chunks = [a.numpy() if hasattr(a, "numpy") else np.asarray(a) for _, _, a in p(text, voice=vpath, speed=speed) if a is not None]
        if not chunks:
            raise SystemExit(f"no audio for: {text!r}")
        audio = np.concatenate(chunks).astype(np.float32)
        # trim the model's leading/trailing near-silence so the timeline controls every pause
        idx = np.where(np.abs(audio) > 0.01)[0]
        if len(idx):
            audio = audio[max(0, idx[0] - int(0.03 * SR)): min(len(audio), idx[-1] + int(0.08 * SR))]
        return audio

    timing = {"lang": lang, "voice": voice, "engine": f"kokoro {REPO}@{REV[:7]}", "sampleRate": SR, "scenes": []}
    for scene in script["scenes"]:
        sdir = out / scene["id"]
        sdir.mkdir(exist_ok=True)
        entries = []
        for n in scene.get("narration", {}).get(lang, []):
            entries += sentences(n)
        items = []
        for i, s in enumerate(entries):
            say = s.get("say", s["text"])
            key = hashlib.sha1(f"{voice}|{speed}|{say}".encode()).hexdigest()[:10]
            f = sdir / f"{i:02d}-{key}.wav"
            if not f.exists():
                for old in sdir.glob(f"{i:02d}-*.wav"):
                    old.unlink()
                sf.write(f, synth(say), SR)
                print(f"  {scene['id']}/{f.name}  {say[:70]}", flush=True)
            items.append({"text": s["text"], "file": str(f.relative_to(out)), "duration": round(sf.info(f).duration, 3)})
        timing["scenes"].append({"id": scene["id"], "sentences": items})
    (out / "timing.json").write_text(json.dumps(timing, indent=1, ensure_ascii=False), encoding="utf-8")
    total = sum(x["duration"] for sc in timing["scenes"] for x in sc["sentences"])
    print(f"tts {lang} ({voice}): {total:.1f}s of speech")


if __name__ == "__main__":
    main(*sys.argv[1:4])
