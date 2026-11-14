# Video series — production pipeline

The chapters of the EventConductor video series, rebuilt from source: one command renders one master,
`./render.sh <chapter> <en|es>`. The plan, the production rules and the chapter table are in
`design/video-series/README.md`. This pipeline extends `media/promo-video` (its `render(t)` contract and
frame-by-frame rendering); nothing rendered is committed — it all goes under `build/` (git-ignored).

## Rules it enforces

- **Nothing drawn by hand.** Product screens are the console of the chapter's app really running,
  filmed frame by frame with Playwright; terminal scenes show commands the pipeline really ran and their
  real output; code on screen is read from the real files at render time (line numbers kept).
  Definition files are resolved whatever their extension (`*.ec.yaml`, `*.yaml`, `*.ec`).
- **Deterministic.** The console page's clock is installed and paused (`page.clock.install` +
  `pauseAt`), advanced 1/30 s per frame (`clock.runFor`), one screenshot per frame at 1920×1080 CSS px
  with `deviceScaleFactor` 2; CSS animations are slowed with CDP `Animation.setPlaybackRate`. The cursor
  and click ring are an overlay that follows Playwright's real mouse. HTML scenes draw any instant with
  `render(t)`. 30 fps, H.264, **no music**.
- **Narration first.** Each scene lasts as long as its own narration in that language (+ padding), so the
  EN and ES masters have their own timelines. Subtitles come from the same TTS sentences.
- **Voice.** Kokoro-82M, local and offline once set up: EN `af_heart`, ES `ef_dora` (both female, fixed
  for the series), from the official `hexgrad/Kokoro-82M` repo at a pinned revision.

## Layout

| Path | What |
|---|---|
| `setup.sh`, `requirements.txt` | npm deps; espeak-ng; an isolated venv (`build/tools/venv`) with the pinned TTS stack and the Kokoro weights |
| `render.sh` | the one command: tts → timeline → record → render → mix + srt → assemble → thumbnail + YouTube texts |
| `chapters/NN/script.json` | scenes: id, view (+ spec), real file excerpts, EN/ES narration (sentences; `{"text","say"}` when the ear needs another spelling than the subtitle), YouTube chapter marks, docs link |
| `chapters/NN/strings.{en,es}.json` | on-screen text of the HTML scenes |
| `chapters/NN/scenes.js` | the chapter's own HTML scenes (diagrams), in the Mateu reel's visual language |
| `chapters/NN/FACTS.md` | each claim of the narration → the file/line or CHANGELOG entry it rests on |
| `chapters/NN/live.mjs` | what is run and filmed: real commands (`c.term`) and console shots (`c.shot`), keyed to the narration's sentences (`s.untilCue(i)`) |
| `scenes/player.html`, `series.js`, `series.css`, `views.js` | the compositor: generic views `title`, `code`, `terminal`, `live`, `close`, `thumb` |
| `scripts/tts.py` | narration → one WAV per sentence + `timing.json` (cached per sentence) |
| `scripts/timeline.mjs` | scene lengths and sentence cues of one master |
| `scripts/record-live.mjs` | the live recorder (paused clock, per-frame screenshots, camera keys): console pages (`c.shot`, a scene may be filmed in parts around a real command), and **Visual Studio Code itself** (`c.vscodeShot`, Playwright `_electron`, the EventConductor extension from the Marketplace — chapter 3) |
| `scripts/render-video.mjs` | frames → silent master; `--stills t1,t2` for single frames; `--thumb` |
| `scripts/mix.py`, `scripts/srt.mjs`, `scripts/assemble.sh` | narration track; `.srt` in EN and ES timed to the master; final encode |
| `scripts/youtube.mjs` | `chNN-youtube.md`: titles, EN/ES descriptions with chapter timestamps, playlist, visibility — for review, nothing is uploaded |
| `scripts/app.sh` | starts the chapter's app fresh on its port (`port` in script.json — 8096 for the series app —, default 8095; `PORT` overrides), stops it |
| `scripts/check-asr.py` | optional self-check: Whisper transcribes every sentence back and flags garbled lines |

## Use

```bash
cd media/video-series
./setup.sh                                  # once: npm, espeak-ng, venv + Kokoro weights (build/tools)
VSCODE=1 ./setup.sh                         # chapter 3: VS Code 1.145.3 + the EventConductor extension (Marketplace)
(cd ../.. && mvn -Djacoco.skip=true install -DskipTests)   # the engine of this checkout (1.0-SNAPSHOT)
./render.sh 04 en                           # → build/out/ch04-en.mp4, ch04-en.srt, ch04-en.es.srt, ch04-thumb.png, ch04-youtube.md
OUT=~/videos ./render.sh 04 es
```

Needs JDK 21, Maven, Node 20+, ffmpeg (libx264), Python 3.12, and the app's port free (8095 for the workshop,
8096 for the series app; the app is started and stopped by the script; `PORT=18195 ./render.sh 05 en` runs it
elsewhere — commands shown on screen then say that port). `MAVEN_ARGS` is passed to every Maven run (e.g. a separate
local repository). Iterating: `SKIP_RECORD=1` reuses the recorded frames, `ONLY=lanes,hotel` re-records
some shots, `node scripts/render-video.mjs 04 en --stills 12,40.5` renders single frames.

### Optional per chapter (additive, chapters 9–14 use them)

- `script.json`: `port` (the app's own port: console title, `ENGINE`, `--server.port`), `prompt` (terminal prompt),
  `appArgs` (Spring arguments; `{here}` = this directory), `before` (shell, at the repo root, before the app starts),
  `apps` (`[{name, app, port, appArgs}]`: more apps, exported to the recorder as `ENGINE_<NAME>`).
- Scene specs: `host` / `app` on a live scene (another app's window title), `pills` on a code scene.
- Recorder: `c.term(id, cmd, { env })`, `c.shot(id, open, script, { context })` (Playwright context options, e.g.
  `httpCredentials`), `c.open(url)` with a full URL for another app's page.
- A narration string with several sentences becomes several cues (`tts.py` splits it); write `{"text": …}` to keep
  it one cue, so `cN` and `s.untilCue(N)` mean the same entry in both languages.
