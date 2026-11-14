# EventConductor video series — plan (draft for review)

**Goal.** EventConductor (EC) does far more than the 1:49 promo can show. A series of short chapters
(2–4 min each) explains one aspect at a time, so people find what they would otherwise miss: sagas,
human tasks, events, operating, testing, deployment. Each chapter is linked from the docs page of its
topic and can be re-rendered whenever the product changes. Nothing is produced yet: this plan comes
first, then two pilots fix style, voice and pacing.

Baseline: **v2.41.0** (2026-11-14). Everything below was checked against `CHANGELOG.md` and, where
marked *verified*, against the workshop app running locally on the 2.41.0 snapshot.

## Production rules

1. **Nothing drawn by hand.** Every screen is EC really running: the web console recorded with
   Playwright, real processes executing on demo data, real terminal output. Code and YAML on screen
   compile/validate and are the files that produced the screen next to them (every definition shown is
   covered by a test in the series app, so `eventconductor:verify` proves it). A chapter is also a
   test: if it cannot be recorded, something is broken — it goes under *Blockers found*. Unlike the
   promo, no staged screens (no illustrated MCP chat, no mock PR-checks panel).
2. **Claims match the product.** Opt-in is said to be opt-in (MCP server, tracing, sharding, process
   index, `compensateLosers` / `compensateAbandoned`, END `compensate: true`, `allowSensitiveVariables`); nothing unreleased is
   shown as available; stand-ins are named as stand-ins (the workshop's fake hotel and fake model are
   labelled *local stand-in* on screen). No superlatives we cannot prove ("first engine with MCP" stays
   out). Never customer names: the demos are the workshop hotel and the repo's samples.
3. **One pipeline, deterministic.** Title and diagram scenes are HTML in the visual language of the
   Mateu reel (`mateu/doc/public/reel.html`: dark ground `#081319`, panels `#0f2530`, ink `#eaf2f2`,
   azure `#4cb2ff` / amber `#f6b23c` / green `#39d98a` / pink `#ff7597` / violet `#a98bff` accents,
   system sans + JetBrains Mono, `rise` / `pop` / `draw` keyframes). Everything is rendered frame by
   frame at 1920×1080: Playwright clock **paused** (`page.clock.install()` + `page.clock.pauseAt()`,
   advanced 1/30 s per frame), CSS animations slowed through CDP `Animation.setPlaybackRate`, one
   screenshot per frame (`deviceScaleFactor` 2 for console footage so the camera can zoom), assembled
   at 30 fps with ffmpeg, H.264. **No background music.**
4. **Voice.** Local, free, offline synthetic voice: **Kokoro** (else Piper). **A female voice in both
   languages (EN and ES), fixed for the whole series, so all three series sound the same.** The
   narration script is written first; each scene lasts as long as its narration (see *Pipeline*).
5. **Two languages.** Two masters per chapter, EN voice and ES voice; each ships with `.srt`
   subtitles in EN **and** ES (YouTube tracks, not burned in). HTML scenes take their text from
   `strings.<lang>.json`; the console stays in English (`workflow.ui.locale` only localises Mateu's own
   words, the engine's pages are English).
6. **Publishing.** The owner's YouTube channel, a dedicated EventConductor playlist. Before uploading
   **each** video, the owner is shown the MP4 (EN and ES) and the texts (title, EN/ES description,
   playlist, visibility) and nothing is uploaded without an explicit OK. Each chapter is embedded/linked
   from its docs page.

## Chapters

App legend: **WS** = `examples/workshop-hotel-booking` (embedded engine, JPA on H2, console at
`:8095`, fake hotel at `/fake/hotel`, fake OpenAI-compatible model at `/fake/llm`, forms engine,
MCP over `/sse`); **SA** = the *series app*, the new `examples/video-series` (WS's hotel plus the extra step-type
definitions, the forms menu, a demo agent catalogue/runtime and an observability compose);
**DC** = `apps/docker-compose.yml` (Postgres + Redpanda + orchestrator/forms/worker/webhook-gateway
images, `2.41.0` published on Docker Hub); **K** = the Helm chart `charts/eventconductor` (0.2.15 /
appVersion 2.41.0) on a local `kind`/`k3d` cluster. Routes are the console's (`/workflow/...`); all
docs paths are under `doc/src/content/docs/`.

| # | Title | Length | What is recorded (real footage) | App | Docs page it lives on |
|---|---|---|---|---|---|
| 1 | **EventConductor in 3 minutes** | 3–3.5' | The idea (HTML) → `06-hotel-booking.yaml` + `HotelTaskHandlers.java` → `/workflow/definitions/hotel-booking` graph → `scripts/start.sh hotel-booking` → process waiting at *Manager approval* → `scripts/approve.sh` → completed; a penthouse run of `rooms-booking-http` keeps the booked rooms and asks the guest, `scripts/answer.sh … cancel` → *Compensated*; `/workflow/processes` search; `mvn verify` output; topologies (HTML) | WS | `index.mdx` (replaces/joins the promo embed, see questions) |
| 2 | **Your first process** | 3' | `pom.xml` (`workflow-engine`, `builtin-workers`), `application.properties` (`workflow.mode=embedded`, `persistence=jpa`), `01-first-booking.yaml`, the `register-booking` `TaskRegistration`; `mvn spring-boot:run`; `curl POST /workflow/api/processes`; the process in the console (Steps, Variables, Diagram with order numbers) | WS | `workshop/01-first-flow.md` |
| 3 | **Definitions: YAML, graph and IDE** | 3' | The `.ec` file as YAML; the graph editor in **VS Code** (`plugins/vscode-eventconductor`: drag a USER_TASK from the palette, Shift+drag a link, set `onIterationFailure` in the panel, the file changing beside it, double-click into an inline PROCESS body — 2.39.0); `mvn eventconductor:validate` catching a broken link; IntelliJ mentioned with a still | SA + VS Code | `guides/ide-plugins.md` |
| 4 | **When a room can't be booked: the guest decides** — sagas and compensation *(pilot)* | 3.5' | `03-rooms-booking-safe.ec.yaml` (`onIterationFailure: continue`, WAIT_FOR_MESSAGE `guest-answer` with a P2D deadline → front desk, END `compensate: true`); `TaskFailure("SOLD_OUT", …, false)`; BOOKING-3 waiting for the guest (2/3, nothing cancelled), lanes per room, Errors tab; `scripts/answer.sh BOOKING-3 cancel` → *Compensated* (*Cancel the room* 2/2); `04-rooms-booking-http.ec.yaml` with the hotel's alternatives, `answer.sh … accept`, `GET /fake/hotel/bookings` → 201 + suite 901; `FailurePathsTest` | WS | `guides/retries-timeouts-compensation.md` |
| 5 | **Calling the outside world** | 3' | `HTTP_CALL` with a named `connection`, `Idempotency-Key`, `output` mapping, `successStatus`; per-room retries visible in the Steps tab; `${secret:NAME}` only from `workflow.http.secrets` (2.27 secure default); HTTP workers in another language (Python, `sdks/`) | WS | `guides/http-calls.md` |
| 6 | **Branches, loops and sub-processes** | 4' | `CHOICE` (first satisfied guard, unguarded default), `FORK`/`JOIN` (XOR join + opt-in `compensateLosers`), `forEach` with `label`, the three `onIterationFailure` policies, `repeat`/`loopBack`, named vs inline `PROCESS` (graph expands into iterations/lanes, double-click opens the child run) | SA (new defs) | `guides/loops-and-multi-instance.md` |
| 7 | **People in the loop** | 3' | `USER_TASK` + `formId: booking-approval` (`forms/booking-approval.ecform`), `assignment.candidateGroups`, `escalation` after P1D, `timeout`; the waiting process (yellow); the manager's form filled in the forms console; the operator's alternative: **Complete step** with a reason in the audit; test kit `advanceTime` + `hasEscalated` | SA (+ FormsMenu) | `guides/user-tasks.md` |
| 8 | **Time and messages** | 3' | `TIMER` ("3 days before check-in, 09:00 hotel time"), step `timeout` + `onTimeoutStepId`, `WAIT_FOR_MESSAGE` correlated by business key, `SEND_MESSAGE`, schedules page (`/workflow/schedules`: create, pause, run now) | SA (new defs) | `guides/scheduling-and-deadlines.md` |
| 9 | **Events in, events out** | 3' | Event triggers (`/workflow/eventTriggers`, *Received events*), Git/generic webhooks, `PUBLISH_EVENT` (built-in worker since 2.35.0), request/response with `REPLY` and synchronous invocation | SA, DC for Kafka | `guides/domain-event-triggers.md` |
| 10 | **AI in the process** | 3.5' | `LLM_CALL` with `responseSchema` and `output` mapping (`05-special-request.yaml`, labelled *local stand-in model*), usage variable, sensitive-variable opt-in; `AGENT` step running against the demo agent catalogue/runtime (labelled *demo stand-in*); one line on pointing `LLM_CALL` at a real provider; MCP: a scripted, deterministic MCP client making real requests to the engine's endpoint | WS + SA | `guides/llm-calls.md` (AGENT part also on `guides/workflow-agents.md`, MCP on `guides/mcp-overview.md`) |
| 11 | **Operating running processes** | 4' | `/workflow/processes`: business-key search, filters (*Only errors*, *Include child processes*), Errors tab with child reasons (2.41.0), Retry from failure vs Restart, Pause/Resume, Set variables, Complete step, Retry from step; `/workflow/batchOperations` (progress, skip reasons); Versions tab and *Migrate…* with dry run | WS + SA | `guides/operating-running-processes.md` |
| 12 | **Testing workflows** | 4' | `@EventConductorTest` + `whenStep(...)`, `advanceTime`, `hasCompensated` / `iterations("rooms")` (2.41.0), scenarios as data (`src/test/scenarios/*.yaml` + `ScenariosTest`), coverage report `target/eventconductor-coverage/coverage.txt`, `mvn verify -Deventconductor.migration.from=…` failing on a gap, then green; the workshop CI workflow on a real PR | WS | `guides/testing-workflows.md` (+ `workshop/testing/automate.md`) |
| 13 | **From laptop to cluster** | 4' | Same definitions in three topologies: embedded memory → embedded JPA (WS) → distributed Kafka (DC: `docker compose up`, Redpanda console topics `http-calls`, `event-publications`…) → Helm on `k3d` (K); Git import + webhook: change a definition, merge, v2 beside v1 in the Versions tab with no redeploy; standalone apps | WS, DC, K | `guides/deployment-modes.md` (Git part: `workshop/10-changing-without-redeploying.md`) |
| 14 | **Secure and observable by default** | 3' | Secure defaults since 2.27/2.28: anonymous refused (401 on the API, `allow-anonymous` only for laptops), secrets only from `workflow.http.secrets`, sensitive variables encrypted at rest; `/actuator/prometheus` metrics, OpenTelemetry tracing (**off by default**, opt-in), Analytics page; sharding and the process index named as **opt-in** scale features | DC + SA observability compose | `guides/security-defaults.md` (new, being written; observability part also on `reference/observability.md`) |

Release status: everything above is released by 2.41.0. Opt-in/experimental items that must be said
so on screen: MCP server (a dependency/config to switch on), tracing (sampling 0.0 by default),
metrics over OTLP, sharding and message routing, the process index, analytics rollups,
`compensateLosers` / `compensateAbandoned`, END `compensate: true` (unreleased until the next release — say so, or record after it ships), `allowSensitiveVariables`, `workflow.test-runs.enabled`,
`trust-forwarded-token` / `allow-anonymous` (workshop-only switches — said every time they appear).
`AGENT_CALL` was removed in 2.34.0 and is not shown; `AGENT` runs against the demo catalogue/runtime of
`examples/video-series` (blocker 1).

### Recording feasibility

- **WS — verified.** Started with `spring-boot:run` (JDK 21, 1.0-SNAPSHOT = 2.41.0): `/`,
  `/workflow/processes`, `/workflow/processes/{id}` (Steps, Messages, Errors, Resources, Variables,
  Diagram), `/workflow/definitions/hotel-booking`, `/workflow/batch-operations` → `batchOperations`,
  `/workflow/steps`, `/workflow/schedules`, `/workflow/eventTriggers`, `/workflow/analytics`,
  `/workflow/tasks` all render. BOOKING-3 (`rooms-booking-safe`) and BOOKING-4 (`rooms-booking-http`)
  wait for the guest with the booked rooms kept; `scripts/answer.sh … cancel` ends them *Compensated* (the fake hotel holds nothing), `… accept` books the hotel's alternative (suite 901) — re-verified on the guest-decides definitions; `hotel-booking` waits at the approval, and
  `scripts/approve.sh` completes it (room booked at the fake hotel); `special-request` completes on the
  fake model; the forEach lanes open on double-click; `/sse` answers the MCP endpoint event. Covers
  chapters 1, 2, 4, 5, 10 (LLM part), 11, 12 and the embedded half of 13.
- **SA — being built** (`examples/video-series` PR). WS plus: definitions for TIMER, WAIT_FOR_MESSAGE/SEND_MESSAGE, FORK/JOIN,
  PUBLISH_EVENT, REPLY, an event trigger and a schedule (each with a scenario in
  `src/test/scenarios/`), `FormsMenu` mounted beside `WorkflowMenu` for chapter 7, a demo agent catalogue/runtime for AGENT,
  and a Prometheus + Grafana compose with tracing on for chapter 14.
- **DC** — images `2.41.0`/`latest` are on Docker Hub (verified via the registry API); Docker 29 is
  installed. Not started for this plan. **K** — `kind`, `k3d`, `kubectl` are installed; not started.
- **VS Code** — the editor only exists inside the IDE plugins (the console's definition view is
  read-only); recording it means driving VS Code itself (Playwright `_electron` with the extension
  loaded, or `code-server` in Chromium) — a new recorder. **IntelliJ** cannot be driven by Playwright:
  a still, or a one-off screen capture flagged as such.
- `demo/local-stack` and `~/IdeaProjects/ec-demo-local` are **not** used: the former runs 2.1.1 images
  and needs a shell rebuilt for Keycloak; the latter is not on this machine.

### Blockers found — all being fixed (owner, 2026-11-14)

Found while checking feasibility; each is **being fixed**, in the PR named.

1. **AGENT could not be recorded running** — it needs a published agent catalogue and an agent runtime
   serving `run-agent@1`, neither in the repo (the workshop runs AGENT only in tests). *Being fixed:*
   a local demo agent catalogue + runtime in a new `examples/video-series` app (**examples/video-series
   PR**), labelled on screen as a demo stand-in.
2. **Compensation iterations unlabelled** — the Steps tab reads `cancel-room-booking · item 0/1` where
   the forward iterations read `rooms · Room 101`; `forEach.label` is not applied to compensations.
   *Being fixed:* **console fix PR**.
3. **Diagram minimap covers nodes at 1280×720** (the *Done* node of `rooms-booking-safe`). *Being
   fixed:* **console fix PR**.
4. **No forms menu in the workshop console** — the manager's approval only goes through
   `scripts/approve.sh`. *Being fixed:* **console fix PR** (forms menu); the recorder signs in with an
   injected test token (decision below).
5. **No runnable definitions for several step types** (TIMER, WAIT_FOR_MESSAGE, SEND_MESSAGE,
   FORK/JOIN, PUBLISH_EVENT, REPLY, schedules, event triggers). *Being fixed:* added, each with a
   scenario test, in the **examples/video-series PR**.
6. **Nothing to show for observability** — no Prometheus/Grafana in the repo, tracing off by default.
   *Being fixed:* a Prometheus + Grafana compose with tracing switched on, in the **examples/video-series
   PR** (the chapter still says tracing is off by default).
7. **No browser-hosted graph editor** — the promo's `scenes/editor-harness.html` is a promo-only page.
   *Being fixed:* the pipeline records the editor in real VS Code via Playwright Electron (or
   `code-server`); IntelliJ appears as a still.
8. **Kokoro / Piper not installed** (Kokoro's Spanish needs `espeak-ng`). *Being fixed:* installed by
   the pipeline's setup step.
9. **No docs page for security defaults** to host chapter 14. *Being fixed:* new
   `guides/security-defaults.md` in a **docs PR**.
10. **MCP with a paid assistant** would be non-deterministic. *Resolved by decision:* a scripted,
    deterministic MCP client making real requests to the real endpoint.
11. **The promo's illustrated scenes and music bed** don't pass these rules. Its HTML/animation code is
    reused, those scenes are not; chapter 12 records a real PR running the workshop CI.

## Pilot 1 · EventConductor in 3 minutes

> **Re-record pilots 1 and 4.** The booking definitions changed (2026-11-14, owner feedback): a room
> the hotel cannot book no longer cancels the rooms already booked on its own. The rooms step keeps
> what was booked (`onIterationFailure: continue`), the hotel proposes an alternative, the guest
> decides, and only a *cancel* — the guest's, or the front desk's after a reminder — runs the
> compensation, through an `END` with `compensate: true`. Any footage of `rooms-booking-safe`,
> `rooms-booking-http` or `hotel-booking` recorded before is out of date. The scripts in
> `media/video-series/chapters/01` and `04` already tell the new story (see their `FACTS.md`).

Recorded from WS. Durations: the longer of the EN and ES narrations at ~150 wpm plus a breath; the
final length of each scene is `max(EN, ES)` TTS + 0.6 s (see *Pipeline*). Total ≈ 3:20 (EN alone
≈ 2:50) — trim scenes 2 and 9 in review if the title must hold to 3 minutes.

| # | Dur. | On screen | EN narration | ES narration |
|---|---|---|---|---|
| 1 | 12 s | HTML title: EC logo, *"Business processes, without the plumbing."*, then the hook word by word | How much of your code is actually the business? EventConductor is an engine for business processes: you write the steps, it handles the plumbing. | ¿Cuánto de tu código es realmente negocio? EventConductor es un motor de procesos de negocio: tú escribes los pasos y él se encarga de la fontanería. |
| 2 | 25 s | HTML diagram: three green chips (*Approve · Book each room · Tell the guest*), then the plumbing chips rising beneath (promo iceberg, reel styling) | A hotel booking is three steps: a manager approves it, each room is booked at the hotel, the guest is told. Production adds the rest: ask the guest when a room is sold out and undo what was booked only if they cancel, retry without booking twice, survive a restart halfway, wait days for a person, change the process while runs are in flight. | Una reserva de hotel son tres pasos: un responsable la aprueba, se reserva cada habitación en el hotel y se avisa al cliente. Producción añade el resto: preguntar al cliente cuando una habitación está agotada y deshacer lo reservado solo si cancela, reintentar sin reservar dos veces, sobrevivir a un reinicio a medias, esperar días a una persona y cambiar el proceso con ejecuciones en marcha. |
| 3 | 21 s | `06-hotel-booking.ec.yaml` typed in, emphasis on `USER_TASK`, `CHOICE`, `forEach`, `Idempotency-Key`, `compensationStepId` + `compensate: true` | With EventConductor you declare the process. This is the real definition: a user task for the approval, a choice on the answer, one HTTP call per room — with retries, a timeout and an idempotency key — and a compensation that cancels the rooms that were booked, only if the guest decides to cancel. | Con EventConductor declaras el proceso. Esta es la definición real: una tarea de usuario para la aprobación, una decisión según la respuesta, una llamada HTTP por habitación —con reintentos, timeout y clave de idempotencia— y una compensación que cancela las habitaciones reservadas, solo si el cliente decide cancelar. |
| 4 | 19 s | `HotelTaskHandlers.java`, the `notify-guest` `TaskRegistration` | Business logic stays in plain Java. A worker is a Spring bean bound to a task: it returns to complete the step, or throws to fail it. It never retries or rolls back by itself — the engine does. | La lógica de negocio sigue siendo Java normal. Un worker es un bean de Spring asociado a una tarea: devuelve un resultado para completar el paso o lanza una excepción para fallarlo. Nunca reintenta ni deshace por su cuenta: eso lo hace el motor. |
| 5 | 15 s | Terminal: `scripts/start.sh hotel-booking BOOKING-7 '{…3 rooms…}'` → `/workflow/processes/{id}` Diagram: *Manager approval* yellow, the rest faded | The engine runs inside your Spring Boot app — here, one process with an embedded database. Start a booking over REST, and the console draws it live: it is waiting for the manager. | El motor corre dentro de tu aplicación Spring Boot; aquí, un único proceso con base de datos embebida. Arrancas una reserva por REST y la consola la dibuja en vivo: está esperando al responsable. |
| 6 | 13 s | Terminal: `scripts/approve.sh BOOKING-7 true 'Regular guest'` → the diagram turns green step by step; Steps tab with `rooms · Room 101/102/103` | The manager approves. The choice takes the approved branch, each room is booked at the hotel, the guest is told. Every step, with its inputs, outputs and timing, is on record. | El responsable aprueba. La decisión toma la rama aprobada, se reserva cada habitación y se avisa al cliente. Cada paso queda registrado, con sus entradas, salidas y tiempos. |
| 7 | 17 s | `rooms-booking-http` BOOKING-8 with room 900 (penthouse) → waiting for the guest (*Find alternatives*, *Ask the guest*); `scripts/answer.sh BOOKING-8 cancel` → *Cancelled by the guest*, amber *Cancel the room at the hotel*, badge *Compensated (100%)*; caption *fake hotel: local stand-in* | Now a booking with a penthouse, which is always sold out. The hotel refuses it, but the rooms it booked stay booked: the hotel offers another room, and the guest decides. The guest cancels — so EventConductor cancels the rooms that were booked, newest first, and the process ends compensated. | Ahora una reserva con un ático, que siempre está agotado. El hotel lo rechaza, pero lo reservado sigue reservado: el hotel ofrece otra habitación y decide el cliente. El cliente cancela, así que EventConductor cancela las habitaciones reservadas, de la más reciente a la más antigua, y el proceso termina compensado. |
| 8 | 15 s | `/workflow/processes`: search `BOOKING-8`, Errors tab (`SOLD_OUT`), back to the list: *Retry from failure*, *Retry all matching* | Operating is built in. Find any run by its business key, see why a step failed, retry from where it stopped, or repair many runs at once with a batch operation. | La operación viene incluida. Encuentras cualquier ejecución por su clave de negocio, ves por qué falló un paso, reintentas desde donde se paró o reparas muchas a la vez con una operación por lotes. |
| 9 | 21 s | Terminal, real output: `mvn verify` in WS — tests, scenarios, coverage table, `eventconductor:verify` BUILD SUCCESS | Definitions are tested like code. The test kit runs a process in milliseconds, with time under your control; scenarios can be plain YAML; and one Maven goal validates every definition and fails the build when the tests leave a branch uncovered. | Las definiciones se prueban como código. El kit de test ejecuta un proceso en milisegundos y con el tiempo bajo tu control; los escenarios pueden ser YAML sin más; y un único goal de Maven valida cada definición y rompe el build si los tests dejan una rama sin cubrir. |
| 10 | 16 s | HTML diagram: embedded (memory → JPA) → distributed (Kafka, orchestrator, workers in any language over HTTP) → Helm | Start embedded, in memory or on a database. When you outgrow one process, the same definitions run distributed over Kafka, with workers in any language over HTTP, deployed with the Helm chart. | Empieza embebido, en memoria o sobre una base de datos. Cuando un solo proceso se te quede corto, las mismas definiciones corren distribuidas sobre Kafka, con workers en cualquier lenguaje vía HTTP, desplegadas con el chart de Helm. |
| 11 | 13 s | `05-special-request.yaml` (`LLM_CALL`, `responseSchema`) → completed run's Variables (`requestCategory`); caption *stand-in model · MCP is opt-in* | It also speaks AI: a step can ask a language model for structured, validated output, and with the MCP server switched on, every operation is a tool for your assistant. | También habla IA: un paso puede pedir a un modelo de lenguaje una salida estructurada y validada y, con el servidor MCP activado, cada operación es una herramienta para tu asistente. |
| 12 | 12 s | HTML close: logo, *Open source · MIT*, eventconductor.mateu.io, github.com/miguelperezcolom/eventconductor, *Next: Your first process* | EventConductor. Open source, MIT. This series takes one topic at a time — start with the hands-on workshop at eventconductor.mateu.io. | EventConductor. Código abierto, licencia MIT. Esta serie trata un tema cada vez; empieza por el taller práctico en eventconductor.mateu.io. |

**On-screen code (real files):**
- `examples/workshop-hotel-booking/src/main/resources/workflows/06-hotel-booking.ec.yaml` (whole file,
  ~200 lines now: it scrolls; the guest-decides half — alternatives, reminder, front desk, `compensate: true` — is below the fold).
- `examples/workshop-hotel-booking/src/main/java/com/example/hotel/workers/HotelTaskHandlers.java`
  — `notifyGuest()` (`TaskRegistration<NotifyGuestInput, NotifyGuestOutput>`).
- `examples/workshop-hotel-booking/scripts/start.sh`, `scripts/approve.sh` (commands as typed).
- `examples/workshop-hotel-booking/src/main/resources/workflows/05-special-request.yaml` —
  `classify-request` step.
- Terminal: `mvn verify` in `examples/workshop-hotel-booking` (coverage floors 90 % / 75 %).

## Pilot 2 · When a room can't be booked: the guest decides — sagas and compensation (chapter 4)

Chosen because compensation is EC's core promise — and the chapter now tells it the way a hotel would:
a sold-out room does not cancel the rooms already booked behind the guest's back. **Compensation is
the mechanism; who decides to trigger it is your business rule.** Fully recordable from WS (re-verified
on the guest-decides definitions; **must be re-recorded**, see the note under Pilot 1). Total ≈ 4:00.
The scene-by-scene script, EN and ES, is `media/video-series/chapters/04/script.json`; the claims are
checked in `chapters/04/FACTS.md`.

| # | Scene | On screen | What it says |
|---|---|---|---|
| 1 | `title` | HTML title *"When a room can't be booked"* · *The guest decides*; three room cards, 900 sold out, the two booked ones: *ask the guest* | Two booked, the third sold out: the hotel does not cancel on its own — it asks the guest. |
| 2 | `byhand` | HTML checklist (*remember what was booked · ask and wait days · remind, then a person · undo in reverse only on cancel · retry without booking twice · late answers*) | What writing it by hand costs. |
| 3 | `declare` | `03-rooms-booking-safe.ec.yaml`, step `rooms`: `forEach`, `onIterationFailure: continue`, `compensable` + `compensationStepId`, `cancel-room-booking` | Keep what was booked; the compensation is how a room is undone — if someone decides so. |
| 4 | `retries` | same file, inline body: `retries: 2`, `timeout: PT30S` | Technical failures: retries with backoff, a timeout. |
| 5 | `decide` | same file: `all-booked` → `ask-guest`, `wait-for-guest` (WAIT_FOR_MESSAGE `guest-answer` by business key, `timeout: P2D` → `tell-front-desk`), `cancelled-by-guest` (`END`, `compensate: true`) | The guest is asked and waited for; no answer is never a cancellation; *cancel* runs the compensation. |
| 6 | `worker` | `HotelTaskHandlers.java`: `TaskFailure("SOLD_OUT", …, false)`, `CancelRoomBookingInput` | A business code, final; the compensation gets the room and its booking id. |
| 7 | `run` | terminal: `scripts/start.sh rooms-booking-safe BOOKING-3 …` (101, 102 double, 900 penthouse) | |
| 8 | `diagram` | BOOKING-3 *Diagram*: `rooms` 2/3 completed anyway, *Cancel the room* not run, waiting at *Wait for the guest's answer* | Nothing cancelled; the guest has been asked. |
| 9 | `lanes` | double-click `rooms` → lanes 101 ✓ 102 ✓ 900 ✗ → *Errors*: `[non-retryable] SOLD_OUT` | Why, down to the child run. |
| 10 | `cancel` | terminal `scripts/answer.sh BOOKING-3 cancel` → *Diagram*: *Compensated (100%)*, *Cancelled by the guest*, amber *Cancel the room* 2/2 | The guest's decision undoes the booked rooms, newest first; the sold-out one had nothing to undo. |
| 11 | `http` | `04-rooms-booking-http.ec.yaml`: `HTTP_CALL` + `Idempotency-Key`, `find-alternatives` (`GET /alternatives`), `book-alternatives` (`answer == 'accept'`), `cancel-room` `DELETE` with `successStatus: [200, 204, 404]` | The same over HTTP, with the hotel's alternative. |
| 12 | `hotel` | terminal: `start.sh rooms-booking-http BOOKING-4` (201 double, 900 penthouse), `answer.sh BOOKING-4 accept`, `curl …/fake/hotel/bookings` → `BOOKING-4-201` double + `BOOKING-4-901` suite; the run *Completed* in the list; caption *fake hotel: local stand-in* | Nothing cancelled, nothing booked twice. |
| 13 | `policies` | HTML: `failFast` · `continue` · `compensateAll` (*all or none, on its own — for one technical unit*); strip *compensation is the mechanism · who triggers it is your rule*: END `compensate: true`, `compensateLosers`, `compensateAbandoned` | When automatic compensation is right (one technical all-or-nothing unit, nobody to ask); a final failure still rolls back on its own; the opt-ins. |
| 14 | `tested` | `FailurePathsTest.java` lines 46–65 (`theGuestCancellingUndoesEveryRoomThatWasBooked`), then the real `mvn test -Dtest=FailurePathsTest` | The guest's answer sent in a test; 101 and 102 compensated, 900 not; each cancellation saw its booking id. |
| 15 | `close` | *Sagas without the bookkeeping* · workshop module 3 · *Next: Calling the outside world* | |

**On-screen code (real files):** `examples/workshop-hotel-booking/src/main/resources/workflows/03-rooms-booking-safe.ec.yaml`,
`04-rooms-booking-http.ec.yaml`, `src/main/java/com/example/hotel/workers/HotelTaskHandlers.java`
(`createRoomBooking()` and `CancelRoomBookingInput`), `src/test/java/com/example/hotel/FailurePathsTest.java`,
`scripts/answer.sh`. Demo data: BOOKING-3 = 101 double, 102 double, 900 penthouse; BOOKING-4 = 201 double,
900 penthouse (the hotel offers suite 901).

`compensate: true` on END is new (CHANGELOG *Unreleased*): record after the release that ships it, or
say *upcoming* on screen.

## Pipeline: how it extends `media/promo-video`

A sibling `media/video-series/` reuses the promo's tooling (Playwright 1.65, the OFL fonts,
`render-animated.mjs`'s `render(t)` contract, `prepare-app.sh`, `capture-terminal.sh`, `assemble.sh`)
and adds what the rules need. Per chapter: `chNN/script.json` (scenes: id, on-screen spec, `en`, `es`
narration), `chNN/scenes.html`, `strings.en.json` / `strings.es.json`.

| Step | Script (new unless noted) | What it does |
|---|---|---|
| 0 | `setup.sh` + `prepare-series-app.sh` (from `prepare-app.sh`) | Installs Kokoro (+ `espeak-ng`), Piper as fallback, the VS Code build for the editor recorder; builds SA (`examples/video-series`) with H2 on file so restarts keep history |
| 1 | `tts.py` + `tts.mjs` | Kokoro (`kokoro` / `kokoro-onnx`), one WAV **per sentence** per language, 24 kHz; writes `build/chNN/audio/<lang>/<scene>.wav` and `timing.json` (each sentence's start/length) |
| 2 | `timeline.mjs` | Scene length = `max(EN, ES)` + 0.6 s; one picture timeline for both masters, so both subtitle tracks stay aligned to scenes. Writes `window.TIMELINE` |
| 3 | `record-console.mjs` (from `record-real.mjs`) | Drives the real console: engine state is prepared first over REST (processes run in real time on the server), then the page is filmed with `page.clock.install()` + `pauseAt()`, advanced 1/30 s per frame (`clock.runFor`), CDP `Animation.setPlaybackRate` slowing CSS animations, one screenshot per frame at `deviceScaleFactor` 2, mouse/keyboard moves scripted per frame |
| 3b | `record-vscode.mjs` (from `record-editor.mjs`) | Launches real VS Code with `plugins/vscode-eventconductor` through Playwright `_electron` (or `code-server` in Chromium) and drives the graph editor frame by frame, the same paused-clock method |
| 4 | `render-animated.mjs` (extended) | HTML scenes per language (`--strings`), real footage frames composited by `render(t)`, piped to ffmpeg → `master.<lang>.mp4` (near-lossless) |
| 5 | `srt.mjs` | Cues from `timing.json`: one sentence per cue, split to ≤ 2 lines × 42 chars; writes `chNN.en.srt`, `chNN.es.srt` |
| 6 | `assemble-chapter.sh` (from `assemble.sh`) | Lays each scene's WAV at its start, muxes AAC 48 kHz, H.264 high yuv420p CRF 18, 30 fps, faststart, **no music** → `ec-chNN-en.mp4`, `ec-chNN-es.mp4`, thumbnail 1280×720 |
| 7 | `review.sh` | Prints the YouTube texts filled from `script.json` for the owner's OK; nothing uploads without it |

**Voice (rule 4: female, both languages, same as the other series).** Kokoro-82M:
- **EN:** `af_heart` (American English, female — Kokoro's best-rated voice); alternate `af_bella`.
- **ES:** `ef_dora` (Spanish, female — Kokoro's only female Spanish voice; needs `espeak-ng` for G2P).
  If its quality fails the pilot listen, fall back to **Piper** with a female Spanish voice (candidate:
  `es_ES-sharvard-medium`, female speaker — confirm by ear), still fixed for the series.
- The voice ids are recorded once and shared with the Mateu series so all three series sound alike.
- Numbers and identifiers are written for the ear in `script.json` (`"101"` → "one oh one" where
  needed) while the subtitles keep the digits.

**Production order.** (0) blockers being fixed in parallel (console fix PR, examples/video-series PR, docs PR); install Kokoro, write steps 1–7 against pilot 4 (all
WS, no new definitions); (1) **pilot 4**, then **pilot 1** — owner review fixes voice, pacing, style;
(2) chapters that WS already covers: 2, 5, 11, 12; (3) SA / `examples/video-series` chapters: 6, 7, 8, 9; (4) 3
(VS Code recorder), 10, 13 (DC/K), 14.

**YouTube text template (per video, shown to the owner before upload):**

```
Title (EN): EventConductor · {N}. {Title} ({mm:ss})
Title (ES): EventConductor · {N}. {Título} ({mm:ss})
Description (EN):
  {One-sentence hook.}
  {Two lines: what the chapter shows, on which demo.}
  Chapters:
  0:00 {scene group} …
  Docs: https://eventconductor.mateu.io/{docs path}/
  Code: https://github.com/miguelperezcolom/eventconductor/tree/v{version}/{example path}
  Recorded on EventConductor v{version}. Every screen is the real product running; the hotel {and the
  model} are local stand-ins. Voice: synthetic (Kokoro). Open source, MIT.
Description (ES): the same, translated (used for the ES master; on the EN master as YouTube's ES translation).
Playlist: EventConductor in N minutes       Visibility: unlisted until the docs link is live, then public
Subtitles: en.srt, es.srt       Language: EN master = English audio; ES master = Spanish audio
```

## Decisions (2026-11-14)

Defaults agreed with the owner; the owner can override any of them.

- **Playlist:** one, **"EventConductor in N minutes"**, holding both languages. The Spanish version is a
  separate video (Spanish title, or the EN title with the suffix "(ES)"). EN and ES `.srt` on both.
- **Chapter 1 and the promo:** chapter 1 lives beside the promo (https://youtu.be/QZhz7KwSfUk). The
  promo stays on the home page; chapter 1 heads the playlist.
- **Thumbnails:** the Mateu reel's dark style, chapter number + short title, one template for all.
- **AGENT:** recorded running against the local demo agent catalogue/runtime shipped in
  `examples/video-series`, labelled on screen as a demo stand-in.
- **MCP:** shown with a scripted, deterministic MCP client making real requests to the real endpoint —
  no paid assistant.
- **AI chapter:** the workshop's stand-in model, labelled on screen as a stand-in, plus one line on how
  to point it at a real provider (`workflow.llm.connections.<name>.provider/model/api-key`).
- **Chapter 14:** lives on the new docs page `guides/security-defaults.md` (being written).
- **Chapter 7 sign-in:** the recorder injects a test token under `workflow.security.trust-forwarded-token`
  — laptop-only, and stated as such in the docs and on screen.
- **Uploads:** unlisted until the docs link is live, then public — and each one only after the owner's
  OK on the MP4s and texts.
