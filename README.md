# EventConductor — Workflow Engine

[![Build and publish](https://github.com/miguelperezcolom/eventconductor/actions/workflows/build-and-publish.yml/badge.svg)](https://github.com/miguelperezcolom/eventconductor/actions/workflows/build-and-publish.yml)
[![CI](https://github.com/miguelperezcolom/eventconductor/actions/workflows/ci.yml/badge.svg)](https://github.com/miguelperezcolom/eventconductor/actions/workflows/ci.yml)
[![CodeQL](https://github.com/miguelperezcolom/eventconductor/actions/workflows/codeql.yml/badge.svg)](https://github.com/miguelperezcolom/eventconductor/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.mateu.workflow/workflow-engine.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.mateu.workflow/workflow-engine)

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
- **REST message delivery** — `POST /workflow/api/messages` (optional `X-Api-Key` guard)
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
| `retryProcess` | Re-trigger all failed (ERROR) steps in a process |
| `pauseProcess` / `resumeProcess` | Pause a process (in-flight work finishes, successors held, clocks frozen) and resume it |
| `pauseWorkflow` / `resumeWorkflow` | Pause/resume a whole definition: all its processes, with new instances born paused |
| `sendMessage` | Deliver an external message to processes waiting on a `WAIT_FOR_MESSAGE` step |
| `getWorkflowAnalytics` | Per-definition analytics: instance counts, rates, durations, per-step stats |
| `findBottleneck` | Find where processes get stuck: slowest step, waiting/running steps, failures |
| `importWorkflowDefinitionsFromGit` | Pull and upsert workflow JSON files from Git |

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
│   ├── forms-engine/          Form definition and rendering engine
│   ├── rule-engine/           Business rule catalog (REST + gRPC read APIs, UI, MCP)
│   ├── rule-runtime/          Lightweight embeddable rule evaluator (JEXL)
│   ├── workflow-maven-plugin/ Build-time validator for workflow/form/rule definitions
│   ├── workflow-e2e/          End-to-end test suite (embedded + JPA/H2 modes)
│   ├── workflow-dist-e2e/     Distributed test suite (Testcontainers: Postgres + Kafka)
│   └── sample-worker/         Hello-world worker example
├── apps/                      Runnable standalone apps + docker-compose.yml
│   ├── orchestrator-standalone-app/  Workflow engine (UI, REST, MCP)
│   ├── forms-standalone-app/  Forms engine
│   ├── rule-standalone-app/   Rule catalog (REST + gRPC + UI + MCP)
│   ├── worker-standalone-app/ Kafka worker
│   └── dev-app/               All engines in one JVM for development
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
│   ├── static/                Screenshot PNG assets
│   └── static-content-server/ Static asset server (own pom, outside the demo reactor)
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
- Workflow definitions loaded from `classpath:/workflows/` at startup (`.json`, `.yaml`, `.yml`).
- Ideal for tests, local development, and embedding in other applications.

---

## Kafka topics (mode: kafka)

| Topic | Direction | Description |
|---|---|---|
| `outbox` | internal | Domain events relayed from the outbox table |
| `upstream` | inbound | Integration events from external services (process creation, timeouts) |
| `downstream` | outbound | Task execution requests sent to workers |

### Kafka configuration (application.properties)

```properties
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.outbox-out-0.destination=outbox
spring.cloud.stream.bindings.upstream-out-0.destination=upstream
spring.cloud.stream.bindings.downstream-out-0.destination=downstream

spring.kafka.bootstrap-servers=localhost:9092
```

---

## Workflow definitions

### File format (`classpath:/workflows/`)

Used in `memory` persistence mode. Each file defines one workflow. Both JSON and YAML are supported (`.json`, `.yaml`, `.yml`).

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
    rollbackable: true
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
    preconditionStepId: step-1
    preconditionExpression: 'false'
```

Every flow enters through a `START` (or a `WAIT_FOR_MESSAGE`) step — a step with no
preconditions of any other type is rejected at load. Compensation steps are anchored to the
step they compensate and guarded with `preconditionExpression: 'false'`, so only the
compensation pipeline (which ignores the guard) ever starts them.

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
      "rollbackable": true,
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
      "topic": "my-worker-topic",
      "preconditionStepId": "step-1",
      "preconditionExpression": "false"
    }
  ]
}
```

### Step types

| Type | Description | Required fields |
|---|---|---|
| `START` | Entry point; completes instantly at process creation (no worker). Must have no preconditions; several STARTs = concurrent entry branches | — |
| `ACTION` | Dispatches a task to a worker | `topic` |
| `USER_TASK` | Pauses the workflow for a human form submission | `formId` |
| `RULE` | Evaluates a business rule; outputs merge into process variables | `ruleId` |
| `PROCESS` | Starts a child workflow as a sub-process; the parent step waits for the child and copies back the variables named in `outputVariables` | `childWorkflowDefinitionId` |
| `TIMER` | Durably pauses the process for a duration or until a date-time | `duration` or `untilVariable` |
| `WAIT_FOR_MESSAGE` | Waits for a message with a matching correlation key (previously named `MESSAGE`) | `messageName`, `correlationExpression` |
| `SEND_MESSAGE` | Emits a message (fire-and-forget) and completes immediately — resumes processes waiting on it | `messageName`, `correlationExpression` |
| `FORK` | Fans out: completes instantly, starting all its successors concurrently (explicit fan-out marker) | — |
| `JOIN` | Barrier: waits until **all** the steps in its `preconditionStepIds` have completed, then completes instantly | `preconditionStepIds` |
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
| `rollbackable` | boolean | `false` | Trigger compensation step on failure |
| `compensationStepId` | string | — | Step to run as compensation (requires `rollbackable: true`) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on successful runs of this step per process instance (loop backstop); `0` inherits the workflow's `defaultMaxStepExecutions` |

### Workflow-level fields

Besides `id`, `name`, `version`, `description`, `status` and the concurrency settings
(`limitConcurrentExecutions`, `maxConcurrentExecutions`, `enqueueOnLimit`):

| Field | Type | Default | Description |
|---|---|---|---|
| `cronExpression` | string (Spring cron, 6 fields) | — | While the definition is ACTIVE, a process instance is created automatically at each occurrence |
| `defaultMaxStepExecutions` | integer | `0` | Default cap on successful executions per step within one process instance; a step's `maxSuccessfulExecutions` overrides it; `0` = unbounded |

### Workflow definition status

| Status | Description |
|---|---|
| `DRAFT` | Under construction, not executable |
| `ACTIVE` | Ready to accept new process instances |
| `DISABLED` | No new instances allowed; running ones continue |
| `ARCHIVED` | Retired definition |

---

## Build-time validation (Maven plugin)

The `workflow-maven-plugin` validates your workflow, form and rule definitions against the
engine's published specifications at build time, failing the build on any violation — so
mistakes are caught in the PR instead of at runtime when the engine loads them. It bundles
the *same* JSON schemas the engine ships (so it can never drift) and adds the semantic checks
a schema cannot express: duplicate/dangling/self-referencing step ids, the entry-point rule
(every step without preconditions must be a `START` or `WAIT_FOR_MESSAGE`), precondition-cycle
detection over the multi-edge graph, the `PROCESS` child-workflow id, cron validity and
JEXL parseability of preconditions for workflows; decision-table row arity and JEXL
parseability for rules.

```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <version>1.0-beta.015</version>
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
for `*.json`, `*.yaml` and `*.yml` — the same layout the engine loads from the classpath. Run
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

# --- Kafka topics ---
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeOutbox-in-0.group=orchestrator-group
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeUpstream-in-0.group=orchestrator-group
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group

# --- Spring Cloud Stream function bindings ---
spring.cloud.stream.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
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
    <version>1.0-beta.015</version>
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
