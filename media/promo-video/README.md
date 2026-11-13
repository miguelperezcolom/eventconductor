# Promo video

The EventConductor promo video (1:43, published on YouTube and embedded on the home page), rebuilt from source.
Script and voice-over: [SCRIPT.md](SCRIPT.md). On-screen text: [strings.json](strings.json).

## How it is made

| Step | Script | What it does |
|---|---|---|
| 1 | `scripts/prepare-app.sh` | Builds `build/app` — a copy of `testbench/workflow-embedded` (embedded engine, JPA on an H2 file, the console) with the [`hotel-booking`](app/definitions/hotel-booking.yaml) definition imported from a directory, and a hotel worker that refuses room 102 ([`ExecutorsConfig`](app/src/main/java/io/mateu/workflow/embedded/ExecutorsConfig.java)). Also `build/gate`, a project with only the test kit, the definition and [`HotelBookingTest`](gate/src/test/java/com/example/booking/HotelBookingTest.java). |
| 2 | `scripts/capture-real.sh` | Runs the app with definition v1 (booking `BK-4711`), screenshots the console states the video shows (`scripts/record-real.mjs`, at 2× so the camera can zoom), changes the definition to v2, restarts and captures the Versions tab. → `build/real/` |
| 3 | `scripts/capture-terminal.sh` | Real output of `mvn test` (green, then red after switching `onIterationFailure` to `continue`) and `mvn eventconductor:validate`. → `build/out/` |
| 3b | `scripts/record-editor.mjs` | Drives the real graph editor in [`scenes/editor-harness.html`](scenes/editor-harness.html) with the mouse — one screenshot per video frame — for the "draw it" scene. → `build/editor/` |
| 4 | `scripts/render-animated.mjs` | `scenes/index.html` draws any moment of the timeline with `render(t)`; this renders every frame (about 3,100) deterministically with Playwright and pipes them to ffmpeg. → `build/master.mp4` |
| 5 | `scripts/assemble.sh` | Encodes the YouTube upload (`build/eventconductor-promo-1080p.mp4`) and a 1280×720 thumbnail. Nothing is committed: the video lives on YouTube. |

```bash
cd media/promo-video
npm ci                      # Playwright + the fonts (Inter, JetBrains Mono — OFL)
npm run build               # steps 1–5; needs Java 21, Maven, ffmpeg (libx264, libvpx-vp9)
```

The engine comes from your local Maven repository (`1.0-SNAPSHOT`): run `mvn -Djacoco.skip=true
install -DskipTests` at the repository root first, so the video shows the console as it is on your
branch. The app listens on port 8197 (`PROMO_PORT` to change it).

Iterating on one scene: `node scripts/render-animated.mjs --stills 21,44,80` renders single frames
to `build/stills/`; `--from 38 --to 55` renders a range. Everything under `build/` is regenerated and git-ignored.
