# EventConductor — Workflow Engine

[![Build and publish](https://github.com/miguelperezcolom/eventconductor/actions/workflows/build-and-publish.yml/badge.svg)](https://github.com/miguelperezcolom/eventconductor/actions/workflows/build-and-publish.yml)
[![CI](https://github.com/miguelperezcolom/eventconductor/actions/workflows/ci.yml/badge.svg)](https://github.com/miguelperezcolom/eventconductor/actions/workflows/ci.yml)
[![CodeQL](https://github.com/miguelperezcolom/eventconductor/actions/workflows/codeql.yml/badge.svg)](https://github.com/miguelperezcolom/eventconductor/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.mateu.workflow/workflow-engine.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.mateu.workflow/workflow-engine)
[![Discord](https://img.shields.io/badge/Discord-Join%20us-5865F2?logo=discord&logoColor=white)](https://discord.gg/4vudTCheNc)

EventConductor is a production-grade, event-driven workflow orchestration platform for the
Java/Spring ecosystem. It covers the full lifecycle of a business process — from definition
to execution to monitoring — without forcing you into BPMN's complexity or an external
SaaS dependency.

---

## Why EventConductor?

### Distributed by design
EventConductor is built from the ground up for distributed environments. Multiple orchestrator
instances coordinate safely using PostgreSQL advisory locks and the outbox pattern, giving you
horizontal scalability with no single point of failure. Scale from a single JVM to a
multi-pod Kubernetes cluster without changing a line of business code.

### Infinitely scalable
Workers are stateless microservices that subscribe to Kafka topics. Add more worker instances
to handle higher load at any time. The engine never becomes a bottleneck — it delegates all
business logic to workers and simply drives the state machine.

### Event-driven, not polling
The orchestration loop is triggered entirely by domain events. No polling loops, no scheduled
queries that grow with your data. Each state transition is an immutable event stored in the
outbox table and relayed to the appropriate handler, keeping latency low and audit trails
complete.

### A DSL designed for business workflows, not diagrams
BPMN was designed to be drawn, not written. EventConductor's JSON workflow DSL was designed
to be owned by developers: human-readable, version-controlled, reviewable in a PR, and
expressive enough to model retries, timeouts, compensation (saga), parallel execution
(steps run as a pure dataflow over the precondition graph, with `FORK`/`JOIN` fan-out and
barriers), child workflows (`PROCESS` steps), conditional branching (JEXL expressions), and
human tasks — all in a single flat file.

### Validated at build time, not at runtime
Because definitions are data owned by developers, the `workflow-maven-plugin` validates your
workflow, form and rule files (JSON/YAML) against the engine's own specifications **during
the build** — duplicate/dangling step references, the entry-point (roots) rule,
precondition-cycle detection, cron validity, JEXL parseability, decision-table arity and full
JSON-schema conformance — so a bad definition fails the PR
instead of the running engine. See [Build-time validation](#build-time-validation-maven-plugin).

### Built-in forms engine
The `forms-engine` module handles form definitions, validation, and rendering. User-task steps
reference a form by ID; the engine takes care of the rest. Forms are defined in JSON, stored
in version control, and served dynamically to any front-end.

### Built-in rule engine
The `rule-engine` module is a catalog of business rules — expression rules and decision
tables, written in JSON or YAML, schema-validated and version-controlled. Rules are
evaluated by the lightweight embeddable `rule-runtime` (JEXL) wherever your data lives:
same JVM, or remotely fetching definitions over **REST or gRPC** with a local cache kept
fresh by Kafka events. `RULE` workflow steps evaluate a rule and merge its outputs into
the process variables.

### Visual tooling included
- **Workflow definition viewer** — a read-only detail view for every definition: property
  summary, the list of steps and an auto-laid-out workflow graph, plus the lifecycle
  actions (promote to production, create working copy, disable, enable, reactivate,
  archive) and one-click **Export YAML**. Definitions themselves stay owned by developers
  as JSON/YAML files (classpath, Git import or database).
- **Drag-and-drop form editor** — build form layouts visually without writing HTML or schemas.

Both are included in the platform UI.

### Full management UI
A web UI is provided out of the box for operators and developers:
- Browse workflow definitions (read-only) and drive their lifecycle — promote, working
  copy, disable/enable, reactivate, archive, export as YAML
- Monitor running process instances and step executions
- Inspect variables, logs, and audit trail per process
- Start, retry and cancel processes manually
- Pause and resume processes — or a whole workflow definition at once — with frozen
  timer/timeout clocks while paused
- Manage form definitions

### Observability, resilience and operations
- **Metrics & tracing** — every engine emits Micrometer metrics when the host app provides a
  `MeterRegistry` (Prometheus scrape at `/actuator/prometheus` in the standalone apps), and the
  standalone apps ship optional OpenTelemetry tracing over OTLP. See the
  [Observability reference](https://miguelperezcolom.github.io/eventconductor/reference/observability/).
- **REST message delivery** — `POST /workflow/api/messages` (`X-Api-Key` or the app's Spring Security; [HTTP security](https://miguelperezcolom.github.io/eventconductor/reference/configuration/#http-security))
  delivers external messages to waiting `WAIT_FOR_MESSAGE` steps, so webhooks and SaaS callbacks can
  resume processes without producing to Kafka.
- **Broker/DB outage resilience** — the orchestrator boots gracefully with Kafka or PostgreSQL
  down and resumes parked work when they return (outbox-based recovery, chaos-tested — see
  [TESTING.md](./TESTING.md)).
- **Built-in analytics** — per-definition instance counts, completion/error rates, durations
  and bottleneck detection, in the UI (Workflow → Analytics) and via the
  `getWorkflowAnalytics` / `findBottleneck` MCP tools. See the
  [Process Analytics guide](https://miguelperezcolom.github.io/eventconductor/guides/analytics/).

### Deploy anywhere, from a unit test to production
Three deployment modes with no code changes:

| Scenario | `workflow.mode` | `workflow.persistence` | External dependencies |
|---|---|---|---|
| Unit tests / embedded library | `embedded` | `memory` | None |
| Demo / dev with H2 | `embedded` | `jpa` | None (H2 in-memory) |
| Single-node with persistence | `embedded` | `jpa` | PostgreSQL / MariaDB / Oracle |
| Full distributed / multi-pod | `kafka` | `jpa` | PostgreSQL + Kafka |

### Native AI integration via MCP — the key differentiator
EventConductor is, to our knowledge, the first workflow engine with a native
[Model Context Protocol (MCP)](https://modelcontextprotocol.io) integration.

Every module exposes its domain as MCP tools. Any MCP-compatible AI client —
Claude Desktop, a custom chatbot, an internal copilot — can connect and operate the
engine in natural language, with no custom integration code:

| What you say | What happens |
|---|---|
| "What is the status of order 123?" | Queries process by business key, returns status + variables |
| "Retry all failed processes from today" | Calls `retryProcess` for each ERROR process |
| "Show me the pending user tasks for the onboarding workflow" | Lists form executions filtered by workflow |
| "Import the new workflow definitions from Git" | Triggers `importWorkflowDefinitionsFromGit` |
| "Cancel booking B-456 and tell me why it failed" | Changes booking status + reads process logs |

The agent is resilient: if one MCP server is down the others continue working normally.
Each server self-describes its domain via a `system-context` MCP Prompt, so the LLM always
has up-to-date context without any code changes.

EventConductor was, to our knowledge, the first workflow engine to ship native MCP support — and it remains the only one where MCP exposure of the engine *and your own business services* is a built-in, embeddable feature rather than a separate connector or add-on.

---

## AI agent via MCP

### Architecture

```
AI client (Claude Desktop, chatbot, copilot…)
        │  MCP over SSE
        ▼
ia-agent-service  (port 8095)
        │  opens a fresh SSE connection per prompt
        ├──► orchestrator MCP server  (port 8105)  — workflow engine tools
        ├──► forms-engine MCP server  (port 8106)  — form definition & execution tools
        └──► booking-service MCP server (port 8108) — domain-specific business tools
```

The agent combines system context from all connected MCP servers with the user prompt
and sends a single request to Claude (Anthropic). Tool calls are executed transparently
and the final answer is returned as plain text or streamed via SSE.

### Available MCP tools

**Workflow engine** (`orchestrator`, port 8105)

| Tool | Description |
|---|---|
| `listProcesses` | All process instances with status and completion % |
| `getProcessDetails` | Full process detail: variables + all step executions |
| `findProcessByBusinessKey` | Look up a process by its business key |
| `getProcessLogs` | Audit trail and log messages for a process |
| `listInFlightProcesses` | Processes in flight (`PENDING`, `RUNNING`, `PAUSED`) from the process-index read model — one indexed read; empty when `workflow.projection.enabled` is off |
| `countProcessesByStatus` | Process counts by status across the fleet, from the process-index read model; empty when the projection is off |
| `retryProcess` | Pick a stopped process up where it stopped: the failed (or cancelled) steps run again. `ERROR` or `CANCELLED` only |
| `restartProcess` | Run a stopped process again from the beginning, succeeded steps included. `ERROR` or `CANCELLED` only |
| `pauseProcess` / `resumeProcess` | Pause a process (in-flight work finishes, successors held, clocks frozen) and resume it |
| `pauseWorkflow` / `resumeWorkflow` | Pause/resume a whole definition: all its processes, with new instances born paused |
| `sendMessage` | Deliver an external message to processes waiting on a `WAIT_FOR_MESSAGE` step |
| `getWorkflowAnalytics` | Per-definition analytics: instance counts, rates, durations, per-step stats |
| `findBottleneck` | Find where processes get stuck: slowest step, waiting/running steps, failures |
| `importWorkflowDefinitionsFromGit` | Pull and upsert workflow JSON files from Git |
| `listWorkflowDefinitions` | The definitions (id, name, status, whether they accept instances) — what a schedule can start |
| `listSchedules` / `getSchedule` | Every schedule (optionally of one workflow) with its source, status and next starts |
| `createSchedule` / `updateSchedule` / `deleteSchedule` | Manage the schedules created through the engine (file-owned ones are changed in their file) |
| `pauseSchedule` / `resumeSchedule` / `runScheduleNow` | Operate any schedule |
| `exportScheduleYaml` | The schedule's `cron.yaml` entry, to promote it to git |
| `listEventTriggers` | Every event trigger (optionally of one workflow): destination and topic, type, filter, business key, source, status |
| `createEventTrigger` / `updateEventTrigger` / `deleteEventTrigger` | Manage the triggers created through the engine (file-owned ones are changed in their file) |
| `pauseEventTrigger` / `resumeEventTrigger` / `exportEventTriggerYaml` | Pause or resume any trigger; get its `events.yaml` entry to promote it to git |
| `listReceivedEvents` | The latest received domain events and what each started or delivered |
| `publishInboundEvent` | Inject a domain event (CloudEvent) on a destination |

**Forms engine** (`forms-engine`, port 8106)

| Tool | Description |
|---|---|
| `listForms` | All form definitions with field count |
| `listFormExecutions` | All pending/completed user tasks |
| `getFormExecution` | Full detail of a form execution including submitted values |
| `importFormsFromGit` | Pull and upsert form JSON files from Git |

**Rule engine** (`rule-engine`, bundled in dev-app / `rule-standalone-app`, port 8107)

| Tool | Description |
|---|---|
| `listRules` | All rule definitions with type, version and tags |
| `getRule` | Full definition of a rule as canonical JSON |
| `saveRule` / `deleteRule` | Create, update or remove a rule (validated on save) |
| `validateRule` | Validate a JSON/YAML rule definition without saving it |
| `evaluateRule` | Evaluate a rule against a JSON object of facts |
| `importRulesFromGit` | Pull and upsert rule JSON/YAML files from Git |

**Custom domain tools** (example: `booking-service`, port 8108)

| Tool | Description |
|---|---|
| `createBooking` | Create a new booking for a lead |
| `listBookings` | All bookings with status |
| `getBooking` | Full booking detail |
| `changeBookingStatus` | Change booking status (Pending / Confirmed / Cancelled) |

Any service can expose its own MCP tools by implementing `McpTools` and annotating
methods with `@Tool`. The agent discovers them automatically.

### Connect Claude Desktop in 5 minutes

1. Start the services:

```bash
# Terminal 1 — orchestrator (workflow engine MCP server)
cd apps/orchestrator-standalone-app
SECURITY_ENABLED=false mvn spring-boot:run

# Terminal 2 — forms engine MCP server
cd apps/forms-standalone-app
SECURITY_ENABLED=false mvn spring-boot:run

# Terminal 3 — AI agent
export ANTHROPIC_API_KEY=sk-ant-...
cd demo/ia-agent-service
mvn spring-boot:run
```

The standalone apps boot with security **on** by default (a password is generated at
startup); `SECURITY_ENABLED=false` disables it for local experimentation. Note that
`demo/ia-agent-service` is not built by the root Maven reactor — it lives in the separate
`demo/` reactor with its own stack (Spring Boot 3.4.1, Spring AI 1.0.0), so run it from
its own directory as shown.

2. Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "eventconductor": {
      "url": "http://localhost:8105/sse",
      "type": "sse"
    },
    "forms-engine": {
      "url": "http://localhost:8106/sse",
      "type": "sse"
    }
  }
}
```

3. Open Claude Desktop. The workflow and forms tools appear automatically in the
   tool palette. Ask anything:

> *"Show me all running processes and retry the ones that are in error."*

### Extend with your own MCP tools

Add a new MCP tool in any Spring service:

```java
@Component
@RequiredArgsConstructor
public class MyMcpTools implements McpTools, McpSystemContext {

    @Override
    public String getSystemContext() {
        return "My domain: describe what the agent can do here.";
    }

    @Tool(description = "Do something useful")
    public String doSomething(String param) {
        // business logic
        return "Done: " + param;
    }
}
```

Then add your service's MCP endpoint to `ia-agent-service/application.yaml`:

```yaml
spring.ai.mcp.client.sse.connections:
  my-service:
    url: http://localhost:MY_PORT
```

The agent will pick up the new tools and incorporate your `getSystemContext()` text
into its system prompt automatically.

---

## AI-assisted development

EventConductor ships curated context files so AI coding tools generate correct
workflow definitions and workers:

- **[`llms.txt`](doc/public/llms.txt)** — an [llms.txt](https://llmstxt.org)-standard
  index of the project (served at the docs-site root as `/llms.txt`).
- **[AI reference — compact](doc/public/eventconductor-ai-compact.md)** — key concepts,
  the workflow DSL, step types, worker API and statuses. Best for day-to-day code
  generation. Paste it into Claude Projects, Cursor Rules, Gemini Gems, etc.
- **[AI reference — full](doc/public/eventconductor-ai-full.md)** — the complete DSL,
  deployment modes, Kafka topics, full Java API, sagas, git-import, forms and MCP.
- **Claude Code / Claude Agent SDK** — bundled skills under
  [`.claude/skills/`](.claude/skills/) (`eventconductor`, `eventconductor-scaffold`,
  `eventconductor-run`) auto-activate for EventConductor tasks.
- **[Workflow definition JSON Schema](modules/workflow-engine/src/main/resources/workflow-definition-schema.json)**
  — add it via `$schema` for editor autocomplete and validation of your definitions.

---

## Repository structure

```
eventconductor/
├── modules/                   Reusable library modules + test suites
│   ├── shared/                DTOs, domain events, DDD base classes
│   ├── workflow-engine/       Core orchestration engine (embeddable Spring Boot library)
│   ├── process-index/         CQRS process-index read model (usable by an out-of-process projector)
│   ├── definition-analysis/   Structural rules of definitions, shared by the engine and the Maven plugin
│   ├── inbound-events-kafka/  Kafka consumer of inbound domain events (event triggers, onEvent waits)
│   ├── webhook-gateway/       Webhooks (GitHub, HMAC-signed) in, as CloudEvents on a destination
│   ├── forms-engine/          Form definition and rendering engine
│   ├── rule-engine/           Business rule catalog (REST + gRPC read APIs, UI, MCP)
│   ├── rule-runtime/          Lightweight embeddable rule evaluator (JEXL)
│   ├── worker-api/            Worker SDK: task handlers and the transport-neutral dispatcher
│   ├── worker-kafka/          Worker transport over Kafka
│   ├── worker-embedded/       Worker transport in the engine's JVM (embedded mode)
│   ├── worker-codegen/        Generates worker project skeletons from task contracts
│   ├── worker-http/           Built-in http-call task (HTTP_CALL steps)
│   ├── worker-llm/            Built-in llm-call task (LLM_CALL steps, Spring AI)
│   ├── workflow-maven-plugin/ Build-time validator for workflow/form/rule definitions
│   ├── workflow-e2e/          End-to-end test suite (embedded + JPA/H2 modes)
│   ├── workflow-dist-e2e/     Distributed test suite (Testcontainers: Postgres + Kafka; -Pdist-e2e)
│   ├── workflow-ui-e2e/       Browser end-to-end tests of the engine's UI (-Pui-e2e)
│   ├── workflow-benchmark/    Benchmark harness (-Pbenchmark)
│   ├── sample-worker/         Hello-world worker example
│   └── test-worker/           Scenario-driven worker for testing workflows
├── apps/                      Runnable standalone apps + docker-compose.yml
│   ├── orchestrator-standalone-app/  Workflow engine (UI, REST, MCP)
│   ├── projector-standalone-app/     Standalone CQRS projector for the fleet-wide process index
│   ├── forms-standalone-app/  Forms engine
│   ├── rule-standalone-app/   Rule catalog (REST + gRPC + UI + MCP)
│   ├── worker-standalone-app/ Test worker (scenario playback + UI)
│   ├── http-worker-standalone-app/   Kafka worker serving HTTP_CALL steps
│   ├── llm-worker-standalone-app/    Kafka worker serving LLM_CALL steps
│   ├── webhook-gateway-standalone-app/  Webhook gateway (signatures → CloudEvents → topic or engine)
│   └── dev-app/               All engines in one JVM for development
├── parents/                   Maven parents for generated workers
│   ├── task-module-parent/    A task library added to any Spring Boot service
│   └── task-service-parent/   A runnable standalone Kafka task service
├── examples/
│   └── greetings-tasks/       Example task module built on task-module-parent
├── testbench/                 Minimal single-purpose apps (embedded workflow/forms/rules)
├── demo/                      Example multi-service system (own Maven reactor,
│   │                          not built by the root build)
│   ├── api-gw/                API gateway
│   ├── booking-service/       Sample business service (orchestrated)
│   ├── content-service/       Sample content service
│   ├── control-plane-service/ Orchestrator host application
│   ├── users-service/         User management
│   ├── shell/                 Web shell / front-end host
│   ├── sdks/                  gRPC client SDK examples
│   ├── ia-agent-service/      AI agent (Claude) with MCP tool integration
│   ├── local-stack/           docker-compose stack running the booking-saga demo locally
│   ├── static/                Screenshot PNG assets
│   └── static-content-server/ Static asset server (own pom, outside the demo reactor)
├── plugins/                   IDE plugins (IntelliJ, VS Code) for definitions
├── charts/                    Helm chart for Kubernetes deployment
└── doc/                       Astro Starlight documentation site
```

---

## Build

```shell
mvn clean install
```

---

## Deployment modes

The engine supports three modes controlled by two independent properties:

| Property | Values | Default |
|---|---|---|
| `workflow.mode` | `kafka` \| `embedded` | `embedded` |
| `workflow.persistence` | `jpa` \| `memory` | `memory` |

The defaults (`embedded` + `memory`) run entirely in-process with no external
dependencies, so you can start small and grow into JPA and then Kafka as your
needs scale.

### Mode 1 — Full distributed (`kafka` + `jpa`)

Requires a running Kafka broker and PostgreSQL database.

```properties
workflow.mode=kafka
workflow.persistence=jpa
```

- Domain events flow through Kafka topics (`outbox`, `upstream`, `downstream`).
- State persisted in PostgreSQL via JPA/Hibernate.
- Multiple orchestrator instances coordinate via PostgreSQL advisory locks.

### Mode 2 — Semi-embedded (`embedded` + `jpa`)

No Kafka required. Works with PostgreSQL, MariaDB/MySQL, Oracle, or H2 (for testing/demos).

```properties
workflow.mode=embedded
workflow.persistence=jpa
```

Application class:

```java
@WorkflowEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow")
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

- Events dispatched in-process via `EmbeddedOutboxRelay` (polls the outbox table every 5 s).
- All state persisted via JPA/Hibernate — survives restarts.
- Useful for single-node deployments or local development with a database.
- Workflow definitions in `classpath:/workflows/` are automatically imported into the database at startup (`ClasspathWorkflowDefinitionImporter`).

### Mode 3 — Fully embedded (`embedded` + `memory`)

Default mode. No Kafka, no database. Everything runs in-process.

```properties
workflow.mode=embedded
workflow.persistence=memory
```

Application class:

```java
@WorkflowEmbeddedApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

- Domain events dispatched synchronously on each repository `save()`.
- State held in `ConcurrentHashMap` (lost on restart).
- Workflow definitions loaded from `classpath:/workflows/` at startup (`.ec`, `.json`, `.yaml`, `.yml`).
- Ideal for tests, local development, and embedding in other applications.

---

## Kafka topics (mode: kafka)

| Topic | Direction | Description |
|---|---|---|
| `outbox` | internal | Domain events relayed from the outbox table |
| `upstream` | inbound | Integration events from external services (process creation, timeouts) |
| `downstream` | outbound | Task execution requests sent to workers |
| `http-calls` | outbound | `HTTP_CALL` requests, served by `http-worker-standalone-app` |
| `llm-calls` | outbound | `LLM_CALL` prompts, served by `llm-worker-standalone-app` |
| `dead-letter` | outbound | Events the engine can never process, parked for inspection and replay |

### Kafka configuration (application.properties)

The engine wires its own bindings — destinations, a consumer group each and the `batch-mode` its
batch consumers require — as defaults you can override. What is left to you is the broker and the
list of functions this application composes:

```properties
spring.kafka.bootstrap-servers=localhost:9092
spring.cloud.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group
```

---

## Workflow definitions

### File format (`classpath:/workflows/`)

Used in `memory` persistence mode. Each file defines one workflow. Both JSON and YAML are supported (`.json`, `.yaml`, `.yml`, and `.ec` — JSON or YAML, sniffed from the content).

A workflow file may say what it is in its name: `order.workflow.yaml` or `order.ec.yaml` (and the
`.yml` twins) are ordinary YAML files that the IDE plugins also open as workflow graphs and validate
against the workflow schema. The compound suffix is not part of the name: a file that declares no
`id` (directory or repository import) gets `order` from either, as `order.ec` would.

**YAML** (recommended for readability):

```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json
id: my-workflow
name: My Workflow
version: 1
description: Optional description
status: ACTIVE
steps:
  - id: start
    type: START
    name: Start

  - id: step-1
    type: ACTION
    name: Do something
    topic: my-worker-topic
    preconditionStepId: start
    timeout: PT30S
    retries: 2
    compensable: true
    compensationStepId: step-compensate

  - id: step-2
    type: USER_TASK
    name: Human approval
    formId: approval-form
    preconditionStepId: step-1

  - id: step-compensate
    type: ACTION
    name: Undo step 1
    topic: my-worker-topic
```

A step runs when its preconditions are met, so a step with none never runs by itself — unless it
is the way in: a `START`, or a `WAIT_FOR_MESSAGE` that begins the flow. Anything else with no
preconditions has to be reachable some other way, and a compensation step is: it is named by the
step it undoes and started by the rollback pipeline. A step that is neither is rejected at load,
because nothing would ever start it.

**JSON** (with IDE schema support via `$schema`):

```json
{
  "$schema": "https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json",
  "id": "my-workflow",
  "name": "My Workflow",
  "version": 1,
  "description": "Optional description",
  "status": "ACTIVE",
  "limitConcurrentExecutions": false,
  "maxConcurrentExecutions": 0,
  "enqueueOnLimit": false,
  "steps": [
    {
      "id": "start",
      "type": "START",
      "name": "Start"
    },
    {
      "id": "step-1",
      "type": "ACTION",
      "name": "Do something",
      "topic": "my-worker-topic",
      "preconditionStepId": "start",
      "timeout": 30000,
      "retries": 2,
      "compensable": true,
      "compensationStepId": "step-compensate"
    },
    {
      "id": "step-2",
      "type": "USER_TASK",
      "name": "Human approval",
      "formId": "approval-form",
      "preconditionStepId": "step-1"
    },
    {
      "id": "step-compensate",
      "type": "ACTION",
      "name": "Undo step 1",
      "topic": "my-worker-topic"
    }
  ]
}
```

### Step types

| Type | Description | Required fields |
|---|---|---|
| `START` | Entry point; completes instantly at process creation (no worker). Must have no preconditions; at most one per workflow (for concurrent entry branches, give it several successors or a `FORK`) | — |
| `ACTION` | Dispatches a task to a worker | — (`topic` defaults to `downstream`) |
| `USER_TASK` | Pauses the workflow for a human form submission | `formId` |
| `RULE` | Evaluates a business rule; outputs merge into process variables | `ruleId` |
| `PROCESS` | Starts a child workflow as a sub-process; the parent step waits for the child and copies back the variables named in `outputVariables` | `childWorkflowDefinitionId` |
| `TIMER` | Durably pauses the process for a duration or until a date-time | `duration` or `untilVariable` |
| `WAIT_FOR_MESSAGE` | Waits for a message with a matching correlation key (previously named `MESSAGE`) | `messageName`, `correlationExpression` |
| `SEND_MESSAGE` | Emits a message (fire-and-forget) and completes immediately — resumes processes waiting on it | `messageName`, `correlationExpression` |
| `FORK` | Fans out: completes instantly, starting all its successors concurrently (explicit fan-out marker) | — |
| `JOIN` | Barrier: waits until **all** the steps in its `preconditionStepIds` have completed, then completes instantly | `preconditionStepIds` |
| `CHOICE` | Exclusive split: takes exactly one successor — the first whose link guard holds (longest guard first; an unguarded link is the default) | — |
| `DYNAMIC` | Dispatches a task like `ACTION`, but the worker's reply may inject new steps into the running process | — |
| `LOCK` | Acquires a named per-key lock (FIFO), serializing processes or critical sections on the same key | `lockKey` |
| `UNLOCK` | Releases a lock taken by a `LOCK` step, admitting the next waiter | `lockKey` |
| `REPLY` | Records the process's reply for a synchronous caller (no worker; the flow carries on) | — |
| `PUBLISH_EVENT` | Publishes a domain event (CloudEvent) from the process's state, via the outbox | `event` |
| `HTTP_CALL` | Calls an HTTP endpoint and maps the response into variables (built-in `http-call` task) | `http` |
| `LLM_CALL` | Asks a language model and maps its answer into variables (built-in `llm-call` task) | `llm` |
| `END` | Marks the workflow as complete | — |

Steps run as a **pure dataflow**: a step starts as soon as all its preconditions have
completed (and its JEXL guard holds), concurrently with every other eligible step — array
order is irrelevant. Every step with no preconditions must be a `START` or a
`WAIT_FOR_MESSAGE`; definitions violating this are rejected at load.

### Step fields

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique identifier within the workflow |
| `type` | enum | — | See step types above |
| `name` | string | — | Human-readable name |
| `description` | string | — | Optional description |
| `preconditionStepId` | string | — | Single step that must complete before this one starts |
| `preconditionStepIds` | string[] | — | Steps that must **all** complete before this one starts; takes precedence over the singular form when non-empty |
| `preconditionExpression` | string | — | JEXL expression over process variables; step is skipped if `false` |
| `parallel` | boolean | `false` | **Deprecated and ignored** — every eligible step runs concurrently; kept only for deserialization of old files |
| `topic` | string | — | Worker topic/destination (ACTION only) |
| `formId` | string | — | Form identifier (USER_TASK only) |
| `ruleId` | string | — | Rule identifier (RULE only) |
| `childWorkflowDefinitionId` | string | — | Child workflow ID (PROCESS only; must differ from the workflow's own id) |
| `outputVariables` | string[] | — | Child variables copied back into the parent when the child completes; empty/absent = none (PROCESS only) |
| `duration` | ISO 8601 duration or integer (ms) | — | How long to wait, counted from step start (TIMER only), e.g. `PT72H` |
| `untilVariable` | string | — | Process variable holding an ISO 8601 date/date-time to wait until; takes precedence over `duration` (TIMER only) |
| `messageName` | string | — | Name of the message this step waits for or emits (WAIT_FOR_MESSAGE / SEND_MESSAGE, required for both) |
| `correlationExpression` | string | — | JEXL expression over process variables yielding the correlation key; use `businessKey` to correlate by business key (WAIT_FOR_MESSAGE / SEND_MESSAGE, required for both) |
| `messageVariables` | string[] | — | Names of the process variables the outgoing message carries; empty/absent = none (SEND_MESSAGE only) |
| `timeout` | integer (ms) | `0` | Max execution time; `0` = no timeout |
| `retries` | integer | `0` | Auto-retry attempts on ERROR or TIMEOUT |
| `compensable` | boolean | `false` | Trigger compensation step on failure |
| `compensationStepId` | string | — | Step to run as compensation (requires `compensable: true`) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on successful runs of this step per process instance (loop backstop); `0` inherits the workflow's `defaultMaxStepExecutions` |

### Workflow-level fields

Besides `id`, `name`, `version`, `description`, `status` and the concurrency settings
(`limitConcurrentExecutions`, `maxConcurrentExecutions`, `enqueueOnLimit`):

| Field | Type | Default | Description |
|---|---|---|---|
| `cronExpression` | string (Spring cron, 6 fields) | — | While the definition is ACTIVE, a process instance is created automatically at each occurrence (shorthand for one schedule without variables) |
| `schedules` | list of `{id?, cron, zone?, variables?}` | — | Scheduled starts with their own zone and initial variables — see [Scheduled starts](#scheduled-starts-cron) |
| `defaultMaxStepExecutions` | integer | `0` | Default cap on successful executions per step within one process instance; a step's `maxSuccessfulExecutions` overrides it; `0` = unbounded |

### Scheduled starts (cron)

A workflow can start itself on a schedule. Each schedule is a Spring cron expression (six fields,
seconds first: `0 0 9 * * MON-FRI` is 09:00 on weekdays), an optional IANA `zone` and optional
initial `variables` for the process it creates. Declare them on the definition:

```yaml
id: daily-report
name: Daily report
cronExpression: "0 0 3 * * *"        # still works: one schedule, no variables, JVM zone
schedules:
  - id: daily-report-emea             # optional: the schedule's name (see below)
    cron: "0 0 9 * * MON-FRI"
    zone: Europe/Madrid               # default: workflow.time.zone (the JVM zone unless set)
    variables:
      region: EMEA
      format: pdf
  - cron: "0 0 9 * * MON-FRI"
    zone: America/New_York
    variables: { region: AMER }
steps: [ ... ]
```

…or keep them out of the definitions, in a **`cron.yaml`** file next to them — the same places
definitions are loaded from: `classpath:/workflows/cron.yaml`, an imported directory
(`workflow.import.directories`) or an imported git repository (searched recursively; `cron.yml`
works too). It is a list of entries naming the workflow they start (a top-level `schedules:` key
holding the list is accepted as well):

```yaml
# workflows/cron.yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/cron-schema.json
- id: daily-report-latam             # optional, one schedule per id
  cron: "0 0 18 * * MON-FRI"
  workflow: daily-report              # the definition id
  zone: Europe/Madrid
  variables:
    region: LATAM
- cron: "@hourly"
  workflow: sweep-expired-carts
```

How it behaves:

- **Only ACTIVE definitions fire**; a disabled or archived one stops every schedule targeting it,
  `cron.yaml` entries included. Paused definitions still create instances, born `PAUSED`.
- **Variables are strings**: numbers and booleans in YAML are read as their text.
- **One schedule per id.** An `id` (letters, digits, `.`, `_`, `-`) names a schedule across the
  engine; it is what a pause is kept under and how a `cron.yaml` entry takes over a schedule created
  from the UI (below). Without one the engine derives it from the workflow and the entry's content
  (`<workflow>~cron`, `<workflow>~s<hash>`, `<workflow>~f<hash>`) — the same on every import, but a
  new one when the entry is edited. A second declaration of an id is reported and never fires.
- **Business keys are deterministic per schedule and occurrence**, so redeliveries and several
  pods collapse into one instance: `<workflow>-cron-<id>-<time with offset>` for a schedule with an
  `id`, whoever declares it; without one, `<workflow>-cron-<local time>` for `cronExpression`
  (unchanged), `<workflow>-cron-s<index>-<time with offset>` for `schedules[index]`, and
  `<workflow>-cron-f<hash>-<time with offset>` for a `cron.yaml` entry (the hash covers its cron, zone
  and variables, so the same entry in two sources starts one process).
- **Missed occurrences are skipped, not replayed** — each schedule's clock starts when the
  scheduler does, and editing a schedule starts its clock afresh.
- **`cron.yaml` is never read as a workflow definition**, by any importer. Each import of a source
  replaces that source's entries: removing an entry (or the file) stops it. An entry with an invalid
  cron or zone, an unknown key, or naming a workflow the engine does not know is logged as an error
  and skipped; the rest still apply. A file that cannot be parsed at all leaves the source's previous
  schedules in place. With `workflow.persistence=jpa` the entries are stored in the `cron_schedule`
  table, so every pod's scheduler sees the same set.
- **Validation**: an invalid `cron` or `zone` in `schedules` rejects the definition on import; the
  Maven plugin validates both forms at build time (and fails on a `cron.yaml` entry naming a
  workflow not defined in the workflows directory). Both IDE plugins validate `cron.yaml` against
  `cron-schema.json`.
- The definition detail page has a **Schedules** tab listing every schedule of the workflow —
  `cronExpression`, `schedules`, `cron.yaml` entries and schedules created from the UI — with its
  zone, variables, next start and status; each id opens the schedule's own page.

#### Managing schedules: UI, REST API and MCP

Schedules can also be created and operated without touching a file — from **Workflow → Schedules**
in the admin console (`/_workflow-admin`, and the all-in-one `WorkflowMenu`), the REST API or the
MCP tools. All three go through the same `ScheduleService`.

| | Declared in a file (definition, `cron.yaml`) | Created from the UI / API / MCP |
|---|---|---|
| Consult (workflow, cron in words, zone, variables, source, next starts) | ✅ | ✅ |
| Pause / resume, run now | ✅ | ✅ |
| Edit (workflow, cron, zone, variables) / delete | ❌ change the file | ✅ |
| Export as a `cron.yaml` entry | ✅ | ✅ |

- **Pause** is kept apart from the schedule, by id (table `cron_schedule_pause`), so it survives
  every re-import of the file that declares the schedule. A paused schedule does not fire; resuming
  does not replay what fell due meanwhile. A schedule with a derived id that is edited in its file is
  another schedule, unpaused — give it an `id` to keep its pause across edits.
- **Run now** starts one process immediately, paused or not, through the same door as an occurrence
  (the engine acting for itself, `SYSTEM`), under the business key
  `<workflow>-cron-manual-<id>-<instant>`.
- Schedules created from the UI/API/MCP are stored by the engine (memory, or the `cron_schedule`
  table under source `ui` with `jpa`). No import ever touches them — except the hand-over below.

**The lifecycle: create in the UI → try it → promote to git → git owns it.** A file is the optional,
durable home of a schedule, not a precondition for having one:

1. Create the schedule from the Schedules page (or `POST /workflow/api/schedules`, or the
   `createSchedule` tool) with an `id`, and let it run; pause it, run it now, edit it until it is right.
2. Its page shows — and `GET /workflow/api/schedules/{id}/yaml` / `exportScheduleYaml` return —
   its `cron.yaml` entry, `id` included. Commit it to the definitions repository.
3. On the next import, **what comes from git overwrites what was set from the UI**, matched by `id`:
   the stored copy is deleted and the file's entry — its cron, zone, variables and workflow — is the
   schedule from then on, with git as its source. Its pause, kept by id, carries over. Same id, same
   business keys: no occurrence fires twice across the hand-over. The same holds for an `id`
   declared in a definition's `schedules`.
4. From then on it is read-only in the UI/API/MCP except for pause, resume and run now. **If it
   stops being in git, it is deleted** — with its pause — like any entry removed from its file; it is
   not handed back to the UI.

REST API — since 2.27.0 the engine's REST API (`/workflow/api/**`) refuses anonymous requests: send an
`X-Api-Key` matching `workflow.message-api.api-key`, or authenticate through the application's Spring
Security; only `workflow.message-api.allow-anonymous=true` opens it (see
[HTTP security](https://miguelperezcolom.github.io/eventconductor/reference/configuration/#http-security)).
Put it behind whatever guards `/_workflow-admin`, since a schedule starts its workflow as the engine itself:

| Method and path | |
|---|---|
| `GET /workflow/api/schedules?workflowId=&q=` | every schedule (one per id) |
| `GET /workflow/api/schedules/{id}` | one schedule, with its next five starts |
| `POST /workflow/api/schedules` | create `{id, workflowId, cron, zone?, variables?}` → `201` |
| `PUT /workflow/api/schedules/{id}` | edit `{workflowId, cron, zone?, variables?}` (`409` for a file-owned one) |
| `DELETE /workflow/api/schedules/{id}` | delete (`409` for a file-owned one) |
| `POST /workflow/api/schedules/{id}/pause` · `/resume` | any schedule |
| `POST /workflow/api/schedules/{id}/run-now` | `202` with the business key of the started process |
| `GET /workflow/api/schedules/{id}/yaml` | its `cron.yaml` entry |

A schedule that breaks a rule (unknown or inactive workflow, invalid id, cron or zone, a taken id)
is answered `400` with every violation in `violations`.

Scanning is tuned with `workflow.cron-scan-interval-ms` (default `10000`) and switched off with
`workflow.cron-enabled=false`.

### Started by — and waiting for — domain events

A definition can start on a **domain event** instead of a clock, and a `WAIT_FOR_MESSAGE` can wait for one
from another system. Events are CloudEvents 1.0 on a logical **destination** — the same
`workflow.events.destinations.<name>.topic` names `PUBLISH_EVENT` publishes to:

```yaml
triggers:
  - id: on-booking-created
    destination: bookings
    type: com.acme.booking.created          # '*' wildcards allowed
    filter: "data.channel == 'web'"         # JEXL over the event
    businessKey: "booking-${data.bookingId}" # default: derived from the event's source + id
    variables: { bookingId: "${data.bookingId}" }
steps:
  # …
  - id: wait-payment
    type: WAIT_FOR_MESSAGE
    name: Wait for the payment
    messageName: payment-captured
    correlationExpression: bookingId
    onEvent:
      destination: payments
      type: com.acme.payment.captured
      correlationKey: "${data.bookingId}"   # default: the record key, else the subject
```

Each event (source + id) starts at most one process per trigger and is correlated at most once. In
kafka mode the engine consumes the destinations' topics (group `workflow.events.consumer.group`); in
any mode `POST /workflow/api/events/{destination}` and the MCP tool `publishInboundEvent` inject one,
and in embedded mode `InboundEventService` (or a Spring `InboundEvent` application event) does, as do
the engine's own `PUBLISH_EVENT`s. Triggers are listed, paused and resumed on **Workflow → Event
triggers**, `/workflow/api/triggers` and MCP; **Workflow → Received events** shows what each event did.
The same triggers, naming the workflow they start, can live in an `events.yaml` next to the definitions
(classpath, directory or git), as schedules can in `cron.yaml` — so wiring a CDC stream or a partner's
events to a workflow is not a change to the workflow — or be created from **Workflow → Event triggers**,
`POST /workflow/api/triggers` or the MCP tool `createEventTrigger`, and promoted to `events.yaml` from
their page. See the guide *Starting on and waiting for domain events*.
Webhooks — GitHub, or any HMAC-signed sender — come in through `webhook-gateway-standalone-app`, which
verifies each delivery and publishes it as a CloudEvent on a destination (guide *Starting a workflow from a webhook*).

### Workflow definition status

| Status | Description |
|---|---|
| `ACTIVE` | Ready to accept new process instances |
| `DISABLED` | No new instances allowed; running ones continue |
| `ARCHIVED` | Retired: as `DISABLED`, and hidden from the listing |

---

## Build-time validation (Maven plugin)

The `workflow-maven-plugin` validates your workflow, form and rule definitions against the
engine's published specifications at build time, failing the build on any violation — so
mistakes are caught in the PR instead of at runtime when the engine loads them. It bundles
the *same* JSON schemas the engine ships (so it can never drift) and adds the semantic checks
a schema cannot express: duplicate/dangling/self-referencing step ids, the entry-point rule
(every step without preconditions must be a `START` or `WAIT_FOR_MESSAGE`), precondition-cycle
detection over the multi-edge graph, the `PROCESS` child-workflow id, cron and zone validity
(`cronExpression`, `schedules` and `cron.yaml`, whose entries must name a defined workflow) and
JEXL parseability of preconditions for workflows; decision-table row arity and JEXL
parseability for rules.

```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <version>${eventconductor.version}</version> <!-- latest: see the Maven Central badge above -->
  <executions>
    <execution>
      <goals>
        <goal>validate</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

The `validate` goal binds to `process-resources` and scans `src/main/resources/{workflows,forms,rules}`
for `*.ec`, `*.ecform`, `*.ecrule`, `*.json`, `*.yaml` and `*.yml` — the same six extensions the
engine imports (a workflow may also be named `*.workflow.yaml` or `*.ec.yaml`, which are `.yaml`
files to every tool), and the same layout it loads from the classpath. Run
it in the build (`mvn verify`) or on demand with `mvn eventconductor:validate`; on a violation
it fails with a per-file report. Directories, per-type toggles, `failOnError`, `failOnMissing`
and `skip` are configurable — see the
[Maven plugin reference](https://miguelperezcolom.github.io/eventconductor/reference/maven-plugin/).

---

## Starting a process

### Via Kafka (mode: kafka)

Send a `ProcessCreationRequested` event to the `upstream` topic:

```json
{
  "type": "process-creation-requested",
  "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [
    { "name": "orderId", "value": "123" },
    { "name": "amount",  "value": "99.90" }
  ]
}
```

### Programmatically (any mode)

Inject `ProcessUpstreamEventUseCase` and call it directly:

```java
@Autowired ProcessUpstreamEventUseCase processUpstreamEventUseCase;

processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested(
        "my-workflow",
        "order-123",
        List.of(
            new Variable("orderId", "123"),
            new Variable("amount",  "99.90")
        )
    )
));
```

---

## Implementing a worker

A worker receives `TaskExecutionRequested` events, performs work, and reports back
`TaskStatusChanged`.

> **Testing a workflow without writing one:** `apps/worker-standalone-app` is a worker that does no
> work — it plays back whatever scenario the process asks for in a `TEST_CONFIG` variable
> (durations, log lines, failures, variables, retries, timeouts), records every task it is given,
> and offers a UI at `/_worker` for changing what a task replies next time. See
> [The Test Worker](doc/src/content/docs/guides/test-worker.md).

### Kafka worker

Subscribe to the `downstream` topic, process the task, then publish to `upstream`:

```java
// Receive
record TaskExecutionRequested(
    String taskExecutionId,
    String processId,
    String workflowDefinitionId,
    String stepId,
    String taskId,
    List<Variable> variables
) {}

// Report back
record TaskStatusChanged(
    String taskExecutionId,
    TaskStatus status,       // COMPLETED | ERROR | RUNNING
    List<Variable> variables // output variables merged into the process
) {}
```

### Embedded worker (mode: embedded)

Provide a bean implementing `EmbeddedTaskExecutor`:

```java
@Bean
public EmbeddedTaskExecutor myWorker(UpdateStepExecutionUseCase updateStepExecution) {
    return request -> {
        // perform work ...
        updateStepExecution.handle(new UpdateStepExecutionCommand(
            request.taskExecutionId(),
            List.of(new Variable("result", "ok")),
            "",
            StepExecutionStatus.COMPLETED
        ));
    };
}
```

`UpdateStepExecutionUseCase` is available as a Spring bean. Inject it wherever needed
to report task progress from asynchronous code.

---

## Process and step execution status

### Process status

| Status | Description |
|---|---|
| `PENDING` | Created, not yet started |
| `RUNNING` | At least one step is executing |
| `COMPLETED` | All steps finished successfully |
| `ERROR` | A step failed after exhausting retries |
| `CANCELLED` | Process was cancelled |

### Step execution status

| Status | Description |
|---|---|
| `CREATED` | Scheduled, waiting for the orchestration loop |
| `PENDING` | Task dispatched to worker, awaiting acknowledgement |
| `RUNNING` | Worker reported it started processing |
| `COMPLETED` | Worker reported success |
| `ERROR` | Worker reported failure |
| `TIMEOUT` | Step exceeded its configured timeout |
| `CANCELLED` | Step was cancelled (e.g. compensation) |

---

## Configuration reference

### Full reference

```properties
# --- Deployment mode ---
workflow.mode=kafka          # kafka | embedded (default: embedded)
workflow.persistence=jpa     # jpa | memory   (default: memory)

# --- Database (workflow.persistence=jpa) ---
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update

# --- Kafka broker (workflow.mode=kafka) ---
spring.kafka.bootstrap-servers=localhost:9092

# --- Kafka topics: the engine's own bindings come as defaults (destinations, a group each,
#     batch-mode). Only the worker's binding is left, and the function list. ---
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group

# --- Spring Cloud Stream function bindings ---
spring.cloud.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

### Minimum config — fully embedded

```properties
workflow.mode=embedded
workflow.persistence=memory
```

Place workflow definitions under `src/main/resources/workflows/` as JSON or YAML files.

---

## Module: workflow-engine

### Maven dependency

```xml
<dependency>
    <groupId>io.mateu.workflow</groupId>
    <artifactId>workflow-engine</artifactId>
    <version>${eventconductor.version}</version> <!-- latest: see the Maven Central badge above -->
</dependency>
```

### Key Spring beans (public API)

| Bean | Description |
|---|---|
| `ProcessUpstreamEventUseCase` | Entry point — start processes and handle integration events |
| `UpdateStepExecutionUseCase` | Report task progress from workers |
| `ProcessRepository` | Read/query process state |
| `StepExecutionRepository` | Read/query step execution state |
| `WorkflowDefinitionRepository` | Manage workflow definitions |

---

## Module: ia-agent-service

AI agent powered by Claude (Anthropic) that lets operators interact with the
orchestration engine in natural language via MCP tools.

See [`demo/ia-agent-service/README.md`](demo/ia-agent-service/README.md) for full documentation.

---

## Local development quickstart

### With Docker Compose (full mode)

The repository ships a ready-to-use compose file at
[`apps/docker-compose.yml`](apps/docker-compose.yml): PostgreSQL (`postgres-db`, port 5432),
a Kafka-compatible Redpanda broker (`redpanda`, port 9092), the Redpanda console (port 8888)
and the published `orchestrator` (port 8105), `forms` (port 8106) and `worker` (port 8107)
images in `kafka` + `jpa` mode:

```shell
docker compose -f apps/docker-compose.yml up -d
```

To run only the infrastructure and start the apps yourself from source:

```shell
docker compose -f apps/docker-compose.yml up -d postgres-db redpanda
cd apps/orchestrator-standalone-app
SECURITY_ENABLED=false mvn spring-boot:run
```

### Without any external dependency (fully embedded)

```properties
# src/main/resources/application.properties
workflow.mode=embedded
workflow.persistence=memory
```

Application class (use `@WorkflowEmbeddedApplication` instead of `@SpringBootApplication`):

```java
@WorkflowEmbeddedApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

Add a workflow definition at `src/main/resources/workflows/hello-world.yaml`:

```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json
id: hello-world
name: Hello World
version: 1
status: ACTIVE
steps:
  - id: start
    type: START
    name: Start

  - id: greet
    type: ACTION
    name: Greet the user
    preconditionStepId: start

  - id: end
    type: END
    name: Done
    preconditionStepId: greet
```

```shell
mvn spring-boot:run
```

## 💬 Community

Questions, ideas, or wondering whether this fits your problem?

👉 **Join the `#eventconductor` channel** on the Mateu Discord: https://discord.gg/4vudTCheNc

Discord is the place for questions, design discussions and "is this a good idea?". **Bugs and
feature requests belong in [GitHub issues](https://github.com/miguelperezcolom/eventconductor/issues)**,
where they can be tracked, referenced from a commit and closed by one — a report in chat scrolls
away. **Security vulnerabilities go through neither**: see [SECURITY.md](./SECURITY.md).

## Versioning & compatibility

EventConductor follows [Semantic Versioning](https://semver.org). From **1.0.0** the stable,
SemVer-covered surface is the **definition formats** (the JSON/YAML/`.ec` documents and their
versioned schemas), the documented **configuration properties**, the **worker/Kafka integration
contract**, the documented **embedded entry points**, the **HTTP message + MCP** surface, and the
`eventconductor.*` **metric names**. The internal Java classes — including the `WorkflowDefinition`
and `Step` model types — are **not** a public API: program against the definition format, not
against those types. Full policy, format versioning and deprecation rules:
[Versioning & Compatibility](https://miguelperezcolom.github.io/eventconductor/reference/versioning/).
