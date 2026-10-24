---
name: eventconductor-run
description: Build and run an EventConductor app to see a workflow actually execute — your own embedded app (spring-boot:run), a repo testbench, or the full distributed stack (docker compose with Postgres + Redpanda/Kafka + orchestrator, forms, workers) — then drive and inspect a process over REST, MCP or the admin UI. Use when asked to run the engine, start a demo, reproduce a process end to end, or verify a change against a real running process (not just tests). Triggers on run the app, start the orchestrator, spring-boot:run, docker compose up, verify the workflow runs, 401/403 from the engine.
---

# Running & verifying an EventConductor process

Pick the smallest topology that exercises the change. For definition logic, a `workflow-test` test
(`eventconductor` skill, `reference/testing.md`) is faster and deterministic — run the app when you need
the real transport, persistence, UI or an external system.

## A. Your own embedded app

```bash
mvn spring-boot:run          # app from the eventconductor-scaffold skill; port 8080
```
- Local access: `workflow.message-api.allow-anonymous=true` (or `workflow.message-api.api-key=dev-key`
  and send `X-Api-Key: dev-key`). Without either, `/workflow/api/**` answers 401 and `/_workflow` 403.
- The REST API, UI and MCP need `spring-boot-starter-webmvc` on the classpath.
- Admin UI: `http://localhost:8080/_workflow` (needs the UI layer: a `@SpringBootApplication` under
  `io.mateu.workflow`, or `@EnableWorkflowUi` + your own Mateu UI mounting `WorkflowMenu`).

Start and inspect a process:
```bash
E=http://localhost:8080/workflow/api
curl -s -X POST "$E/processes" -H 'X-Api-Key: dev-key' -H 'Content-Type: application/json' \
     -d '{"definitionId":"order-processing","businessKey":"order-1","variables":{"amount":"99.90"}}'
curl -s "$E/processes/by-key?businessKey=order-1" -H 'X-Api-Key: dev-key'
curl -s "$E/processes/<id>/steps" -H 'X-Api-Key: dev-key'
curl -s "$E/processes/<id>/logs"  -H 'X-Api-Key: dev-key'
```
Resume a `WAIT_FOR_MESSAGE` / `awaitReply`:
```bash
curl -s -X POST "$E/messages" -H 'X-Api-Key: dev-key' -H 'Content-Type: application/json' \
     -d '{"messageName":"payment-confirmed","correlationKey":"order-1","variables":{"paid":"true"}}'   # 202
```
Inject a domain event (triggers / `onEvent`): `POST $E/events/<destination>` with a structured CloudEvent
(`{"specversion":"1.0","id":"e-1","source":"test","type":"com.acme.booking.created","data":{...}}`).

## B. Repo testbenches (no wiring)

`testbench/` is a separate Maven reactor that depends on the `modules/` SNAPSHOT jars — build those
first (`mvn -q -pl modules/workflow-engine -am install -DskipTests`; the jacoco gate may need
`-Djacoco.skip=true`).
```bash
cd testbench/workflow-embedded-headless && mvn spring-boot:run      # embedded + memory, starts a process on boot
cd testbench/workflow-embedded-db-headless && mvn spring-boot:run   # embedded + jpa (H2)
cd apps/orchestrator-standalone-app && WORKFLOW_MODE=embedded WORKFLOW_PERSISTENCE=memory \
   SECURITY_PASSWORD=changeme mvn spring-boot:run                   # full console, login admin/changeme
```

## C. Full distributed stack (docker compose)

```bash
SECURITY_PASSWORD=changeme docker compose -f apps/docker-compose.yml up -d
```
| Service | Port | Notes |
|---|---|---|
| postgres-db | 5432 | db `workflow` / `workflow` / `secret` |
| redpanda (Kafka) | 9092 (host), 19092 (in network) | console on 8888 |
| orchestrator | 8105 | `WORKFLOW_MODE=kafka`, `WORKFLOW_PERSISTENCE=jpa`; HTTP Basic `admin:$SECURITY_PASSWORD`; also serves `HTTP_CALL` / `LLM_CALL` / `PUBLISH_EVENT` (built-in workers on `http-calls`, `llm-calls`, `event-publications`) |
| forms | 8106 | forms engine + task inbox |
| worker | 8107 | test worker (scenarios from a `TEST_CONFIG` variable; UI `/_worker`) |
| webhook-gateway | 8109 | webhooks → CloudEvents |

Load your definitions into the orchestrator: mount a directory and set `WORKFLOW_DEFINITIONS_DIRS`
(directory import at startup), or a git import (`workflow.git-import.repositories`), or the admin UI.
Call it with Basic auth: `curl -u admin:changeme http://localhost:8105/workflow/api/processes`.
In Kafka mode you can also start a process with a `ProcessCreationRequested` JSON on `upstream`
(produce with `rpk ... --compression none`).

`demo/` services expect the dev infra of `.dev/docker-compose.yml` (broker on `localhost:9192`).

## MCP

The engine exposes MCP tools (Spring AI MCP server; Mateu `/mateu/mcp`) — `listProcesses`,
`getProcessDetails`, `sendMessage`, `listIncidents`, `retryIncident`, `createBatchOperation`,
`updateProcess`… (no start-process tool: start over REST, a trigger, or `updateWithStart`). Needs
Spring Security or `allow-anonymous` (403 otherwise). Claude Desktop: `doc/src/content/docs/guides/mcp-claude-desktop.md`.

## What to check when it "doesn't run"

- **401 / 403** → secure defaults (see A).
- **Nothing happens after start** → embedded: no `EmbeddedTaskExecutor`/`TaskHandler` for the step
  (the step stays `PENDING`); Kafka: no worker consuming `downstream` / the step's `topic`.
- **Step stuck `PENDING`** → the worker never reported, or reported with the wrong `taskExecutionId`;
  give steps a `timeout`. `eventconductor.steps.stalled` counts them.
- **Process never completes** → an AND-JOIN waiting on a branch that was not taken, or a step waiting on
  a message nobody sends (messages are not buffered).
- **Definition ignored** → not under `classpath:/workflows/` (or the configured import dirs), or invalid
  (startup log; `mvn eventconductor:validate`).
- **HTTP_CALL fails `HTTP_FORBIDDEN_AUTH` / `HTTP_FORBIDDEN_HOST`** → host binding / allowed hosts
  (`eventconductor` skill, `reference/security.md`).
- Process states to expect: `COMPLETED`, `COMPENSATED` (saga rolled back), `ERROR`, `CANCELLED`; steps
  `CREATED → PENDING → RUNNING → COMPLETED`, `AWAITING_RETRY` between attempts.
