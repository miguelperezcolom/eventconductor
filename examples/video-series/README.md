# The series app

What the EventConductor video series records that the [workshop](../workshop-hotel-booking) does not have: the
same hotel, embedded engine on JPA/H2, console and forms console, with one runnable, tested definition for every
step type the series shows. Nothing leaves the laptop — the hotel, the channel manager and the taxi companies
are **local stand-ins** under `/fake/**`, and the agent catalogue and runtime are a **demo stand-in** (rules, not
a model), so every run is deterministic.

## Run it

```bash
# once, at the repository root: the engine from source
mvn -Djacoco.skip=true -DskipTests install

cd examples/video-series
mvn spring-boot:run                 # http://localhost:8096 — Workflow and Forms menus
scripts/seed.sh                     # a realistic mix: waiting, completed, compensated, incidents
```

`mvn verify` runs the tests (scenarios in `src/test/scenarios`, the kit tests, and `SeriesAppEndToEndTest`, which
boots the whole app on port 18096), then `eventconductor:verify` checks the workflow coverage floors.

The app is laptop-only: `workflow.message-api.allow-anonymous=true` (no login for the API, console and MCP) and
`workflow.security.trust-forwarded-token=true` (the forms API believes an unverified token, so
`scripts/approve.sh` can act as a manager). Never on a network — see the
[secure-by-default guide](../../doc/src/content/docs/guides/security-defaults.md).

## What is in it

| Definition (`src/main/resources/workflows`) | Shows | Chapter |
|---|---|---|
| `hotel-booking.ec.yaml` | the workshop's booking: USER_TASK + form, escalation, timeout, CHOICE, HTTP forEach saga | 7 |
| `group-arrival.ec.yaml` | CHOICE (+ default), XOR JOIN, FORK / AND JOIN, forEach over a **named** PROCESS with label and `onIterationFailure: continue` | 6 |
| `prepare-room.ec.yaml` | the named sub-process: `repeat` (one step until clean) and `loopBack` (inspect → fix, again) | 6 |
| `airport-transfer.ec.yaml` | a race: FORK, XOR JOIN with `compensateLosers` (opt-in), late completion undone | 6 |
| `balance-reminder.ec.yaml` | TIMER `until` "3 days before check-in, 09:00 Madrid", WAIT_FOR_MESSAGE by business key with a `deadline` and `onTimeoutStepId` | 8 |
| `guest-checkin.ec.yaml` + `room-cleaning.ec.yaml` | SEND_MESSAGE → WAIT_FOR_MESSAGE between processes, correlated by room; step `timeout` + `onTimeoutStepId` | 8 |
| `nightly-occupancy.ec.yaml` + `cron.yaml` | a schedule (03:00 Europe/Madrid): Schedules page, pause, run now | 8 |
| `loyalty-statement.ec.yaml` | CONTINUE_AS_NEW (a monthly chain under one business key) | 8 |
| `booking-onboarding.ec.yaml` + `events.yaml` | an event trigger, fed by the generic webhook; PUBLISH_EVENT | 9 |
| `room-quote.ec.yaml` | request/response: synchronous invocation answered by REPLY | 9 |
| `special-request-agent.ec.yaml` | AGENT against the demo catalogue/runtime, CHOICE on its JSON result | 10 |
| `channel-sync.ec.yaml` | an HTTP_CALL that becomes an incident while the channel manager is down | 11 |

| Code (`src/main/java/com/example/series`) | |
|---|---|
| `workers/SeriesTaskHandlers` | the ACTION handlers (plain `TaskRegistration` beans, deterministic) |
| `agents/DemoAgentCatalogue` | **demo stand-in** for the IA catalogue: publishes `front-desk-assistant` and its output schema (AGENT refs are checked against it at import) |
| `agents/DemoAgentRuntime` | **demo stand-in** for the agent runtime: serves `run-agent@1`, answers by rules, checks its result against the schema |
| `fakes/*` | **local stand-ins**: hotel (`/fake/hotel`), channel manager with an outage switch (`/fake/channel-manager`), taxis (`/fake/taxis`), and `GET /fake/events` — what PUBLISH_EVENT published |
| `mcp/McpToolsConfig` | puts the engine's MCP tools on the app's MCP server (`/sse`) |

## Scripts

| | |
|---|---|
| `scripts/start.sh <definition> <key> '<json>'` | start a process (`POST /workflow/api/processes`) |
| `scripts/approve.sh <key> true\|false ['comment']` | decide a booking's approval through the forms API, as manager "mia" |
| `scripts/message.sh <name> <key> ['<json>']` | send a message (`payment-received`, `room-ready`…) |
| `scripts/webhook.sh <bookingId> <guest> <checkIn>` | a signed channel-manager webhook → `booking-onboarding` |
| `scripts/invoke.sh <key> <roomType> <nights>` | a synchronous `room-quote` invocation, answered by its REPLY |
| `scripts/seed.sh` | fills the console (run once on a fresh app) |
| `node scripts/mcp-demo.mjs [bookingId]` | a **scripted, deterministic MCP client**: real JSON-RPC to `/sse` — initialize, `tools/list`, `listWorkflowDefinitions`, `publishInboundEvent` (starts `booking-onboarding` through its trigger), `findProcessByBusinessKey`, `getProcessLogs`. Node 18+, no dependencies |

The channel manager stays down after the seed, so the incidents can be retried on camera:
`curl -X POST 'http://localhost:8096/fake/channel-manager/outage?on=false'`, then *Retry from failure*.

## Observability (chapter 14)

```bash
docker compose --profile observability up -d
mvn spring-boot:run -Dspring-boot.run.profiles=observability    # tracing ON; it is OFF by default
scripts/seed.sh
```

- Grafana http://localhost:3000 → dashboard **EventConductor** (anonymous viewer; `admin`/`admin` to edit):
  running processes, open incidents, stalled steps, starts and finishes, step outcomes and durations, retries and
  compensations, HTTP calls, events and webhooks — the engine's own metrics from `/actuator/prometheus`.
- Prometheus http://localhost:9090 (scrapes `host.docker.internal:8096` every 5 s).
- Jaeger http://localhost:16686, service `video-series`: one trace per process, a span per step.

## Chapter → what to record

| # | Record |
|---|---|
| 6 | `group-arrival` GROUP-1 (lanes per room, one *Error*: room 304 out of order, the step still completes), `prepare-room` children (repeat, loopBack), `airport-transfer` TRANSFER-1 (amber *Cancel Airport Express*) |
| 7 | `hotel-booking` BOOKING-7 waiting at *Manager approval*; Forms → Tasks; or `scripts/approve.sh BOOKING-7 true` |
| 8 | `balance-reminder` BALANCE-1 (timer armed for 09:00), BALANCE-2 (waiting for the payment, deadline shown), `scripts/message.sh payment-received BALANCE-2`; `guest-checkin` CHECKIN-1 + `scripts/start.sh room-cleaning CLEAN-305 '{"room": "305"}'`; Schedules page (`nightly-occupancy`) |
| 9 | `scripts/webhook.sh CM-2002 Bea 2026-12-24` → Received events, the onboarding, `curl localhost:8096/fake/events`; `scripts/invoke.sh QUOTE-2 double 2` |
| 10 | `special-request-agent` AGENT-1 / AGENT-2 (Variables: `preparation`), labelled *demo stand-in*; `node scripts/mcp-demo.mjs` |
| 11 | SYNC-2/3/4 incidents, the outage switch, Retry from failure, batch retry |
| 14 | the observability profile above |
