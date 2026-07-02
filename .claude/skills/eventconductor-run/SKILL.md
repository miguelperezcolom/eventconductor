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
WORKFLOW_MODE=embedded WORKFLOW_PERSISTENCE=memory mvn spring-boot:run
```

Or run a headless testbench that starts a process on boot and logs the run — the quickest way
to see orchestration without any wiring:

```bash
mvn -q -pl testbench/workflow-embedded-headless -am spring-boot:run      # embedded + memory
mvn -q -pl testbench/workflow-embedded-db-headless -am spring-boot:run   # embedded + jpa (H2)
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

## Drive a process to verify

Embedded/from-source with the UI or REST up, or programmatically via
`ProcessUpstreamEventUseCase` (see the `eventconductor` skill). In Kafka mode, send a
`ProcessCreationRequested` to the `upstream` topic. Then confirm:

- process reaches `COMPLETED` (or the expected `ERROR`/`CANCELLED`);
- each `StepExecution` transitions `CREATED → PENDING → RUNNING → COMPLETED`;
- output variables were merged (query `ProcessRepository.findByBusinessKey(...)`).

## What to check when it "doesn't run"

- Nothing happens after start → in embedded mode, is there an `EmbeddedTaskExecutor` bean? In
  Kafka mode, is the worker consuming `downstream` and publishing to `upstream`?
- Step stuck in `PENDING` → the worker isn't reporting back, or is reporting with the wrong
  `taskExecutionId`.
- Process never `COMPLETED` → missing `END`, or parallel branches without a `JOIN`.
- Definition ignored → not under `classpath:/workflows/`, missing `name`/`steps`, or `status`
  not `ACTIVE`.

## Build order

Standalone apps and testbenches depend on the `modules/` jars. If you changed a module, build
it first (`mvn -q -pl modules/workflow-engine -am install`) or run with `-am` so Maven builds
the reactor dependencies.
