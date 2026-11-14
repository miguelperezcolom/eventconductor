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
   index, `compensateLosers` / `compensateAbandoned`, `allowSensitiveVariables`); nothing unreleased is
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
| 1 | **EventConductor in 3 minutes** | 3–3.5' | The idea (HTML) → `06-hotel-booking.yaml` + `HotelTaskHandlers.java` → `/workflow/definitions/hotel-booking` graph → `scripts/start.sh hotel-booking` → process waiting at *Manager approval* → `scripts/approve.sh` → completed; a penthouse run of `rooms-booking-http` ends *Compensated*; `/workflow/processes` search; `mvn verify` output; topologies (HTML) | WS | `index.mdx` (replaces/joins the promo embed, see questions) |
| 2 | **Your first process** | 3' | `pom.xml` (`workflow-engine`, `builtin-workers`), `application.properties` (`workflow.mode=embedded`, `persistence=jpa`), `01-first-booking.yaml`, the `register-booking` `TaskRegistration`; `mvn spring-boot:run`; `curl POST /workflow/api/processes`; the process in the console (Steps, Variables, Diagram with order numbers) | WS | `workshop/01-first-flow.md` |
| 3 | **Definitions: YAML, graph and IDE** | 3' | The `.ec` file as YAML; the graph editor in **VS Code** (`plugins/vscode-eventconductor`: drag a USER_TASK from the palette, Shift+drag a link, set `onIterationFailure` in the panel, the file changing beside it, double-click into an inline PROCESS body — 2.39.0); `mvn eventconductor:validate` catching a broken link; IntelliJ mentioned with a still | SA + VS Code | `guides/ide-plugins.md` |
| 4 | **All rooms or none: sagas and compensation** *(pilot)* | 3.5' | `03-rooms-booking-safe.yaml`; `TaskFailure("SOLD_OUT", …, false)`; BOOKING-3 *Compensated*, diagram (2/3, amber *Cancel the room* 2/2), lanes per room, Errors tab; `04-rooms-booking-http.yaml` + `GET /fake/hotel/bookings` → `[]`; `FailurePathsTest` | WS | `guides/retries-timeouts-compensation.md` |
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
`compensateLosers` / `compensateAbandoned`, `allowSensitiveVariables`, `workflow.test-runs.enabled`,
`trust-forwarded-token` / `allow-anonymous` (workshop-only switches — said every time they appear).
`AGENT_CALL` was removed in 2.34.0 and is not shown; `AGENT` runs against the demo catalogue/runtime of
`examples/video-series` (blocker 1).

### Recording feasibility

- **WS — verified.** Started with `spring-boot:run` (JDK 21, 1.0-SNAPSHOT = 2.41.0): `/`,
  `/workflow/processes`, `/workflow/processes/{id}` (Steps, Messages, Errors, Resources, Variables,
  Diagram), `/workflow/definitions/hotel-booking`, `/workflow/batch-operations` → `batchOperations`,
  `/workflow/steps`, `/workflow/schedules`, `/workflow/eventTriggers`, `/workflow/analytics`,
  `/workflow/tasks` all render. BOOKING-3 (`rooms-booking-safe`) and BOOKING-4 (`rooms-booking-http`)
  end *Compensated* and the fake hotel holds nothing; `hotel-booking` waits at the approval, and
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

Recorded from WS. Durations: the longer of the EN and ES narrations at ~150 wpm plus a breath; the
final length of each scene is `max(EN, ES)` TTS + 0.6 s (see *Pipeline*). Total ≈ 3:20 (EN alone
≈ 2:50) — trim scenes 2 and 9 in review if the title must hold to 3 minutes.

| # | Dur. | On screen | EN narration | ES narration |
|---|---|---|---|---|
| 1 | 12 s | HTML title: EC logo, *"Business processes, without the plumbing."*, then the hook word by word | How much of your code is actually the business? EventConductor is an engine for business processes: you write the steps, it handles the plumbing. | ¿Cuánto de tu código es realmente negocio? EventConductor es un motor de procesos de negocio: tú escribes los pasos y él se encarga de la fontanería. |
| 2 | 25 s | HTML diagram: three green chips (*Approve · Book each room · Tell the guest*), then the plumbing chips rising beneath (promo iceberg, reel styling) | A hotel booking is three steps: a manager approves it, each room is booked at the hotel, the guest is told. Production adds the rest: undo what was booked when a later room fails, retry without booking twice, survive a restart halfway, wait days for a person, change the process while runs are in flight. | Una reserva de hotel son tres pasos: un responsable la aprueba, se reserva cada habitación en el hotel y se avisa al cliente. Producción añade el resto: deshacer lo reservado cuando falla una habitación posterior, reintentar sin reservar dos veces, sobrevivir a un reinicio a medias, esperar días a una persona y cambiar el proceso con ejecuciones en marcha. |
| 3 | 21 s | `06-hotel-booking.yaml` typed in, emphasis on `USER_TASK`, `CHOICE`, `forEach`, `Idempotency-Key`, `compensationStepId` | With EventConductor you declare the process. This is the real definition: a user task for the approval, a choice on the answer, one HTTP call per room — with retries, a timeout and an idempotency key — and a compensation that cancels a room if the booking as a whole fails. | Con EventConductor declaras el proceso. Esta es la definición real: una tarea de usuario para la aprobación, una decisión según la respuesta, una llamada HTTP por habitación —con reintentos, timeout y clave de idempotencia— y una compensación que cancela la habitación si la reserva en conjunto falla. |
| 4 | 19 s | `HotelTaskHandlers.java`, the `notify-guest` `TaskRegistration` | Business logic stays in plain Java. A worker is a Spring bean bound to a task: it returns to complete the step, or throws to fail it. It never retries or rolls back by itself — the engine does. | La lógica de negocio sigue siendo Java normal. Un worker es un bean de Spring asociado a una tarea: devuelve un resultado para completar el paso o lanza una excepción para fallarlo. Nunca reintenta ni deshace por su cuenta: eso lo hace el motor. |
| 5 | 15 s | Terminal: `scripts/start.sh hotel-booking BOOKING-7 '{…3 rooms…}'` → `/workflow/processes/{id}` Diagram: *Manager approval* yellow, the rest faded | The engine runs inside your Spring Boot app — here, one process with an embedded database. Start a booking over REST, and the console draws it live: it is waiting for the manager. | El motor corre dentro de tu aplicación Spring Boot; aquí, un único proceso con base de datos embebida. Arrancas una reserva por REST y la consola la dibuja en vivo: está esperando al responsable. |
| 6 | 13 s | Terminal: `scripts/approve.sh BOOKING-7 true 'Regular guest'` → the diagram turns green step by step; Steps tab with `rooms · Room 101/102/103` | The manager approves. The choice takes the approved branch, each room is booked at the hotel, the guest is told. Every step, with its inputs, outputs and timing, is on record. | El responsable aprueba. La decisión toma la rama aprobada, se reserva cada habitación y se avisa al cliente. Cada paso queda registrado, con sus entradas, salidas y tiempos. |
| 7 | 17 s | `rooms-booking-http` BOOKING-8 with room 900 (penthouse) → Diagram: `rooms` red, amber *Cancel the room at the hotel*, badge *Compensated (100%)*; caption *fake hotel: local stand-in* | Now a booking with a penthouse, which is always sold out. The hotel refuses it, so EventConductor cancels the rooms that were already booked, on its own, newest first. The process ends compensated: nothing is left booked. | Ahora una reserva con un ático, que siempre está agotado. El hotel lo rechaza, así que EventConductor cancela por su cuenta las habitaciones ya reservadas, de la más reciente a la más antigua. El proceso termina compensado: no queda nada reservado. |
| 8 | 15 s | `/workflow/processes`: search `BOOKING-8`, Errors tab (`SOLD_OUT`), back to the list: *Retry from failure*, *Retry all matching* | Operating is built in. Find any run by its business key, see why a step failed, retry from where it stopped, or repair many runs at once with a batch operation. | La operación viene incluida. Encuentras cualquier ejecución por su clave de negocio, ves por qué falló un paso, reintentas desde donde se paró o reparas muchas a la vez con una operación por lotes. |
| 9 | 21 s | Terminal, real output: `mvn verify` in WS — tests, scenarios, coverage table, `eventconductor:verify` BUILD SUCCESS | Definitions are tested like code. The test kit runs a process in milliseconds, with time under your control; scenarios can be plain YAML; and one Maven goal validates every definition and fails the build when the tests leave a branch uncovered. | Las definiciones se prueban como código. El kit de test ejecuta un proceso en milisegundos y con el tiempo bajo tu control; los escenarios pueden ser YAML sin más; y un único goal de Maven valida cada definición y rompe el build si los tests dejan una rama sin cubrir. |
| 10 | 16 s | HTML diagram: embedded (memory → JPA) → distributed (Kafka, orchestrator, workers in any language over HTTP) → Helm | Start embedded, in memory or on a database. When you outgrow one process, the same definitions run distributed over Kafka, with workers in any language over HTTP, deployed with the Helm chart. | Empieza embebido, en memoria o sobre una base de datos. Cuando un solo proceso se te quede corto, las mismas definiciones corren distribuidas sobre Kafka, con workers en cualquier lenguaje vía HTTP, desplegadas con el chart de Helm. |
| 11 | 13 s | `05-special-request.yaml` (`LLM_CALL`, `responseSchema`) → completed run's Variables (`requestCategory`); caption *stand-in model · MCP is opt-in* | It also speaks AI: a step can ask a language model for structured, validated output, and with the MCP server switched on, every operation is a tool for your assistant. | También habla IA: un paso puede pedir a un modelo de lenguaje una salida estructurada y validada y, con el servidor MCP activado, cada operación es una herramienta para tu asistente. |
| 12 | 12 s | HTML close: logo, *Open source · MIT*, eventconductor.mateu.io, github.com/miguelperezcolom/eventconductor, *Next: Your first process* | EventConductor. Open source, MIT. This series takes one topic at a time — start with the hands-on workshop at eventconductor.mateu.io. | EventConductor. Código abierto, licencia MIT. Esta serie trata un tema cada vez; empieza por el taller práctico en eventconductor.mateu.io. |

**On-screen code (real files):**
- `examples/workshop-hotel-booking/src/main/resources/workflows/06-hotel-booking.yaml` (whole file;
  the trailing `rejected` / `cancel-room` steps may scroll).
- `examples/workshop-hotel-booking/src/main/java/com/example/hotel/workers/HotelTaskHandlers.java`
  — `notifyGuest()` (`TaskRegistration<NotifyGuestInput, NotifyGuestOutput>`).
- `examples/workshop-hotel-booking/scripts/start.sh`, `scripts/approve.sh` (commands as typed).
- `examples/workshop-hotel-booking/src/main/resources/workflows/05-special-request.yaml` —
  `classify-request` step.
- Terminal: `mvn verify` in `examples/workshop-hotel-booking` (coverage floors 90 % / 75 %).

## Pilot 2 · All rooms or none: sagas and compensation (chapter 4)

Chosen because it is EC's core promise, it is fully recordable today from WS (verified), and it
exercises the hardest console views (lanes, compensation colouring, child-run errors). Total ≈ 3:35.

| # | Dur. | On screen | EN narration | ES narration |
|---|---|---|---|---|
| 1 | 13 s | HTML title *"All rooms or none"* · *Sagas and compensation*; three room cards, 900 turns red | A booking for three rooms. Two are booked, the third is sold out. What happens to the two that were booked? This chapter: sagas and compensation in EventConductor. | Una reserva de tres habitaciones. Dos se reservan y la tercera está agotada. ¿Qué pasa con las dos que ya se reservaron? En este capítulo: sagas y compensación en EventConductor. |
| 2 | 20 s | HTML diagram: the hand-written version as a checklist that keeps growing (*remember what was booked · undo in reverse · backoff · idempotency · give up on silence · late answers*) | Written by hand, this is where things go wrong. You have to remember which rooms were booked, undo them in reverse order, retry with backoff without booking twice, give up on a hotel that never answers — and cope with an answer that arrives after you gave up. | Escrito a mano, aquí es donde las cosas salen mal. Tienes que recordar qué habitaciones se reservaron, deshacerlas en orden inverso, reintentar con espera creciente sin reservar dos veces, rendirte con un hotel que no contesta… y lidiar con una respuesta que llega después de haberte rendido. |
| 3 | 23 s | `03-rooms-booking-safe.yaml`, step `rooms`: highlight `forEach`, `compensable`, `compensationStepId`, `onIterationFailure: compensateAll`; then the `cancel-room-booking` step at the bottom | In EventConductor it is declared. The rooms step runs once per room, each room a small flow of its own. Compensable, with a compensation step: if the booking fails, every room that was booked is cancelled. And on iteration failure, compensate all: let every room finish, and if one failed, undo the rest. | En EventConductor se declara. El paso de habitaciones se ejecuta una vez por habitación, cada una con su pequeño flujo. Compensable, con un paso de compensación: si la reserva falla, se cancela cada habitación reservada. Y ante el fallo de una iteración, compensarlo todo: deja que terminen todas y, si una falló, deshaz las demás. |
| 4 | 16 s | Same file, inline body: highlight `retries: 2`, `timeout: PT30S` | Inside each room, two more lines. Retries: a technical failure is retried twice, with exponential backoff. Timeout: a worker that never answers fails the attempt after thirty seconds — and that is retried too. | Dentro de cada habitación, dos líneas más. Reintentos: un fallo técnico se reintenta dos veces, con espera exponencial. Timeout: un worker que nunca responde hace fallar el intento a los treinta segundos, y ese intento también se reintenta. |
| 5 | 17 s | `HotelTaskHandlers.java`: the `TaskFailure("SOLD_OUT", …, false)` line, then `record CancelRoomBookingInput(Room room, String roomBookingId)` | The worker says why it failed, with a business code, and says the failure is final: retrying will not make the penthouse free. The compensation receives the room and the booking id it has to cancel. | El worker dice por qué falló, con un código de negocio, y que el fallo es definitivo: reintentar no va a liberar el ático. La compensación recibe la habitación y el identificador de la reserva que tiene que cancelar. |
| 6 | 7 s | Terminal: `scripts/start.sh rooms-booking-safe BOOKING-3 '{"guest":"Bob","rooms":[101 double, 102 double, 900 penthouse]}'` → JSON reply | Let's run it. Booking three: rooms 101 and 102, and the penthouse, 900. | Vamos a ejecutarlo. Reserva tres: las habitaciones 101 y 102, y el ático, la 900. |
| 7 | 20 s | `/workflow/processes/{BOOKING-3}` → *Diagram* tab: badge *Compensated (100%)*, `rooms` red **2/3**, dashed red edge to amber *Cancel the room* **2/2**, *Tell the guest* not reached; camera on each | The process ends Compensated. Book every room is red: two of three. The dashed red edge leads to Cancel the room, drawn amber because it undid work instead of doing it — two of two, one cancellation per room that had been booked. Tell the guest never ran. | El proceso termina compensado. «Book every room» está en rojo: dos de tres. La línea roja discontinua lleva a «Cancel the room», en ámbar porque deshizo trabajo en lugar de hacerlo: dos de dos, una cancelación por cada habitación reservada. A «Tell the guest» nunca se llegó. |
| 8 | 15 s | Double-click `rooms` → lanes *Room 101* ✓, *Room 102* ✓, *Room 900* ✗ → *Errors* tab: `rooms · Room 900 › create-room-booking` — `[non-retryable] SOLD_OUT` | Double-click the step and it opens into one lane per room: 101 and 102 completed, 900 failed. The Errors tab says why, down to the child run: sold out, not retryable. | Con doble clic, el paso se abre en un carril por habitación: la 101 y la 102 completadas, la 900 fallida. La pestaña de errores dice por qué, hasta la ejecución hija: agotada, sin reintento. |
| 9 | 22 s | `04-rooms-booking-http.yaml`: highlight `connection: hotel`, `Idempotency-Key`, the `cancel-room` DELETE and `successStatus: [200, 204, 404]` | The same saga works when the hotel is an HTTP API and there is no worker to write. One call per room, with a stable idempotency key per room, so a retry never books twice. The compensation is a DELETE — and a 404 counts as success, because cancelling is idempotent too. | La misma saga funciona cuando el hotel es una API HTTP y no hay worker que escribir. Una llamada por habitación, con una clave de idempotencia estable por habitación, así que un reintento nunca reserva dos veces. La compensación es un DELETE, y un 404 cuenta como éxito, porque cancelar también es idempotente. |
| 10 | 10 s | Terminal: `scripts/start.sh rooms-booking-http BOOKING-4 '{…201 double, 900 penthouse…}'`, the run *Compensated* in the list, then `curl -s localhost:8095/fake/hotel/bookings` → `[]`; caption *fake hotel: local stand-in* | Run it with the penthouse, and ask the hotel what it holds: nothing. The room that was booked has been cancelled at the hotel. | Lánzalo con el ático y pregunta al hotel qué tiene: nada. La habitación que se reservó se ha cancelado en el hotel. |
| 11 | 28 s | HTML diagram: the three policies side by side — `failFast` (default) · `continue` · `compensateAll`; a green *Compensated* badge next to a red *Error* badge; a small strip *since 2.39 · opt-in: `compensateLosers` (XOR join) · `compensateAbandoned` (END)* | Compensate all is one of three policies. Fail fast, the default, stops at the first failure. Continue books what it can and reports the rest. And compensated is not an error: the process did what it was written to do. Since 2.39, the losing branches of a race and the branches an END cuts short can be undone too — both opt-in. | Compensarlo todo es una de tres políticas. Fallo rápido, la de por defecto, se detiene en el primer fallo. Continuar reserva lo que puede e informa del resto. Y compensado no es un error: el proceso hizo lo que se le pidió. Desde la 2.39, también se pueden deshacer las ramas perdedoras de una carrera y las que un END deja a medias; ambas cosas son opcionales. |
| 12 | 16 s | `FailurePathsTest.java` lines 27–44, then the real `mvn test -Dtest=FailurePathsTest` green line | And it is tested. The test kit runs the saga in milliseconds and asserts it room by room: only 900 failed, 101 and 102 were compensated, and each cancellation saw the booking id of its room. | Y está probado. El kit de test ejecuta la saga en milisegundos y la comprueba habitación por habitación: solo falló la 900, la 101 y la 102 se compensaron, y cada cancelación recibió el identificador de su reserva. |
| 13 | 10 s | HTML close: *Sagas without the bookkeeping* · link to workshop module 3 · *Next: Calling the outside world* | Sagas without the bookkeeping. Next: calling the outside world over HTTP. Module 3 of the workshop is linked below. | Sagas sin llevar la cuenta a mano. Próximo capítulo: llamar al mundo exterior por HTTP. El módulo 3 del taller está enlazado abajo. |

**On-screen code (real files):**
- `examples/workshop-hotel-booking/src/main/resources/workflows/03-rooms-booking-safe.yaml` (steps
  `rooms` with its inline body, and `cancel-room-booking`).
- `examples/workshop-hotel-booking/src/main/java/com/example/hotel/workers/HotelTaskHandlers.java` —
  `createRoomBooking()` (the `TaskFailure` line) and `CancelRoomBookingInput`.
- `examples/workshop-hotel-booking/src/main/resources/workflows/04-rooms-booking-http.yaml` (`rooms`
  `http:` block and `cancel-room`).
- `examples/workshop-hotel-booking/src/test/java/com/example/hotel/FailurePathsTest.java` —
  `compensateAllCancelsEveryRoomThatWasBooked`.
- Demo data: BOOKING-3 = 101 double, 102 double, 900 penthouse; BOOKING-4 = 201 double, 900 penthouse.

Depends on blockers 2 and 3 (compensation labels, minimap), being fixed in the console fix PR.

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
