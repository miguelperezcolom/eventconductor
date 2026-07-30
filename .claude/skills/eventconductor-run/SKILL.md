---
name: eventconductor-run
description: Build and run an EventConductor app to see a workflow actually execute — fully embedded (no deps) or full distributed (Docker Compose with Postgres + Redpanda/Kafka). Use when asked to run the engine, start a demo, reproduce a process end to end, or verify a change against a real running process (not just tests). Triggers on run the app, start the orchestrator, spring-boot:run, docker compose up, verify the workflow runs.
---

# Running & verifying an EventConductor process

This is the EventConductor-specific version of the generic `run`/`verify` skills. Pick the
smallest topology that exercises your change.

## Fastest: fully embedded (no external dependencies)

```bash
cd apps/orchestrator-standalone-app
WORKFLOW_MODE=embedded WORKFLOW_PERSISTENCE=memory SECURITY_ENABLED=false mvn spring-boot:run
```

The standalone apps default to `eventconductor.security.enabled=true` (`SECURITY_ENABLED`);
set `SECURITY_ENABLED=false` for local runs or the UI/REST endpoints will demand login.

Or run a headless testbench that starts a process on boot and logs the run — the quickest way
to see orchestration without any wiring:

```bash
# testbench/ is a separate Maven reactor — run from inside the module, not with -pl from the root
cd testbench/workflow-embedded-headless && mvn spring-boot:run      # embedded + memory
cd testbench/workflow-embedded-db-headless && mvn spring-boot:run   # embedded + jpa (H2)
```

The `*-headless` testbenches use `@WorkflowEmbeddedApplication` + an `ApplicationRunner` that
creates a process at startup; watch the logs for step transitions to `COMPLETED`.

## Full distributed (Kafka + PostgreSQL)

```bash
docker compose -f apps/docker-compose.yml up -d     # Postgres + Redpanda + the 3 standalone apps
```
This brings up `orchestrator` (`WORKFLOW_MODE=kafka`), `forms`, and `worker`. To run the
orchestrator from source against those infra services instead:
```bash
cd apps/orchestrator-standalone-app && mvn spring-boot:run   # picks up kafka/jpa config
```

**Two compose files, two broker ports.** `apps/docker-compose.yml` exposes Redpanda on
`localhost:9092` (the standalone apps). The `demo/` services instead expect the dev infra from
`.dev/docker-compose.yml`, which exposes the broker on `localhost:9192` — start that one before
running a `demo/` service, or it will hang looking for `localhost:9192`.

## Drive a process to verify

Embedded/from-source with the UI or REST up, or programmatically via
`ProcessUpstreamEventUseCase` (see the `eventconductor` skill). In Kafka mode, send a
`ProcessCreationRequested` to the `upstream` topic. Then confirm:

- process reaches `COMPLETED` (or the expected `ERROR`/`CANCELLED`);
- each `StepExecution` transitions `CREATED → PENDING → RUNNING → COMPLETED`;
- output variables were merged (query `ProcessRepository.findByBusinessKey(...)` — returns `Optional`).

To resume a process waiting on a **MESSAGE** step in a live run, POST the message over REST:
```bash
curl -X POST http://localhost:8080/workflow/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"messageName": "payment-confirmed", "correlationKey": "order-123", "variables": {"paid": "true"}}'
```
Responds 202; add `-H 'X-Api-Key: ...'` if `workflow.message-api.api-key` is set. The MCP
`sendMessage` tool does the same.

To cancel a process stuck waiting, call
`cancelProcessUseCase.handle(new CancelProcessCommand(processId))` (or the UI): the process and
its pending/running steps go `CANCELLED`, and late worker reports are ignored.

## What to check when it "doesn't run"

- Nothing happens after start → in embedded mode, is there an `EmbeddedTaskExecutor` bean? In
  Kafka mode, is the worker consuming `downstream` and publishing to `upstream`?
- Step stuck in `PENDING` → the worker isn't reporting back, or is reporting with the wrong
  `taskExecutionId`.
- Process never `COMPLETED` → missing `END`, or parallel branches without a `JOIN`.
- Definition ignored → not under `classpath:/workflows/`, or it doesn't parse (check the
  startup log). The classpath loader imports every parseable file regardless of `status`; the
  `name`+`steps` minimum applies only to the Git importer.

## Build order

Standalone apps and testbenches depend on the `modules/` jars. If you changed a module, build
it first (`mvn -q -pl modules/workflow-engine -am install`) or run with `-am` so Maven builds
the reactor dependencies.
