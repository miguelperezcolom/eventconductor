# EventConductor — AI Reference (Full)

EventConductor is a production-grade, event-driven **workflow / saga orchestration engine** for the Java/Spring ecosystem. You describe a business process as a **workflow definition** (JSON or YAML) composed of **steps**. The engine drives the state machine — scheduling steps, enforcing preconditions, retries, timeouts, and compensation — and delegates all business logic to **workers** (stateless microservices in Kafka mode, or in-process beans in embedded mode).

It scales from a single JVM with no external dependencies up to a multi-pod Kubernetes cluster (Kafka + PostgreSQL, coordinating via Kafka partition ownership and the outbox pattern) **without changing business code** — only two configuration properties.

---

## 1. Maven dependencies

All artifacts are under `io.mateu.workflow` and share one version (2.33.0 at the time of writing —
check Maven Central / `CHANGELOG.md` for the newest). Spring Boot 4.1, Java 21. There is no archetype
or BOM: a project is a Spring Boot app with these dependencies.

```xml
<dependency>  <!-- the engine -->
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-engine</artifactId>
  <version>${eventconductor.version}</version>
</dependency>
<dependency>  <!-- test kit: JUnit 5 + Spring test + AssertJ + @EventConductorTest -->
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-test</artifactId>
  <version>${eventconductor.version}</version>
  <scope>test</scope>
</dependency>
<!-- build plugin: <plugin> io.mateu.workflow:workflow-maven-plugin, goal `validate` (see §16) -->
```

| Add | When |
|---|---|
| `org.springframework.boot:spring-boot-starter-webmvc` | to serve the REST API, admin UI or MCP (optional in workflow-engine) |
| `forms-engine` | `USER_TASK` steps (forms, task inbox) |
| `rule-engine` / `rule-runtime` | `RULE` steps |
| `worker-embedded` | typed `TaskHandler`s in the engine's JVM (needed for the built-in tasks below when embedded) |
| `worker-http` | `HTTP_CALL` in-process (+ `worker-embedded`) |
| `worker-llm` + `spring-ai-anthropic` / `spring-ai-openai` | `LLM_CALL` / `AGENT_CALL` in-process (+ `worker-embedded`) |
| `worker-kafka` | a worker service over Kafka |
| `inbound-events-kafka` | kafka mode with event `triggers` / `onEvent` waits |
| `flyway-core` (+ `flyway-database-postgresql`) | `workflow.persistence=jpa` (the engine runs its own migrations) |

Prebuilt images (Docker Hub `miguelperezcolom/*`): `orchestrator-standalone-app`, `forms-standalone-app`,
`worker-standalone-app` (test worker), `http-worker-standalone-app`, `llm-worker-standalone-app`,
`rule-standalone-app`, `webhook-gateway-standalone-app`, `projector-standalone-app`.

---

## 2. Deployment modes

Two independent properties control everything. No code changes to switch.

| Property | Values | Default | Meaning |
|---|---|---|---|
| `workflow.mode` | `embedded` \| `kafka` | `embedded` | How domain events are dispatched |
| `workflow.persistence` | `memory` \| `jpa` | `memory` | Where state is stored |

### Mode 1 — Fully embedded (`embedded` + `memory`)  ← default

No Kafka, no database. Everything runs in-process. Ideal for unit tests, local dev, and embedding in another app.

```properties
workflow.mode=embedded
workflow.persistence=memory
```

- Domain events dispatched synchronously on each repository `save()`.
- State held in `ConcurrentHashMap` — lost on restart.
- Definitions loaded from `classpath:/workflows/` at startup.
- Use `@WorkflowEmbeddedApplication` instead of `@SpringBootApplication` (excludes the web/UI/JPA layers from scanning). Kafka & JPA autoconfig are excluded automatically.

### Mode 2 — Semi-embedded (`embedded` + `jpa`)

In-process dispatch, state in a supported DB (PostgreSQL, MariaDB/MySQL, Oracle, or H2 for demos). Survives restarts.

```properties
workflow.mode=embedded
workflow.persistence=jpa
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=validate   # with flyway-core the engine applies its own migrations
```

- Main class: `@WorkflowEmbeddedApplication` plus `@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")` and `@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")`.
- Without `flyway-core` the engine warns and runs without indexes (`ddl-auto` creates none). Migrations target PostgreSQL and H2.
- Events dispatched in-process via `EmbeddedOutboxRelay` (polls the outbox table).
- Definitions in `classpath:/workflows/` are imported to the DB at startup; can also be imported from Git.

### Mode 3 — Full distributed (`kafka` + `jpa`)

Requires Kafka + a supported DB. Designed for production multi-pod deployments.

```properties
workflow.mode=kafka
workflow.persistence=jpa
spring.kafka.bootstrap-servers=localhost:9092
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.cloud.function.definition=consumeOutbox;consumeUpstream   # + consumeWorkerEvent if a worker runs here
# ... datasource as above; bindings are contributed by the engine, see §10
```

- Domain events flow through Kafka topics (`outbox`, `upstream`, `downstream`).
- Multiple orchestrator instances coordinate via Kafka partition ownership: events are keyed by process, so each process is owned by exactly one instance, fenced by an optimistic-locking version on the aggregates for the rebalance window. (Embedded mode, with no partitions, falls back to a per-process row lock — `SELECT … FOR UPDATE`.)
- Use a normal `@SpringBootApplication`.

The philosophy: **start fully embedded, grow into JPA and then Kafka** as scale demands.

### Secure by default (2.27 / 2.28)

- `/workflow/api/**` (REST) lets a request in when `X-Api-Key` matches `workflow.message-api.api-key`,
  or no key is set and a Spring Security filter chain exists, or `workflow.message-api.allow-anonymous=true`;
  otherwise **401**.
- The consoles — admin UI (`/_workflow`, `/_workflow-admin`, `/_forms…`, `/_rules`), MCP (`/mateu/mcp`),
  rule reads, task-project zips — need Spring Security or `allow-anonymous=true` (the api key does not
  open them); otherwise **403**. `workflow.ui.enabled=false` unmounts the admin UI.
- An embedded app that mounts the engine's UI (`WorkflowMenu`/`FormsMenu`/`RulesMenu`) adds
  `@EnableWorkflowUi` beside `@WorkflowEmbeddedApplication` (since 2.28).
- Standalone apps use HTTP Basic: `SECURITY_ENABLED` (default true), `SECURITY_USER` (`admin`),
  `SECURITY_PASSWORD` (set it — a generated one is never printed).
- Local development: `workflow.message-api.allow-anonymous=true`. Production: Spring Security, or an api
  key + `workflow.ui.enabled=false`.
- More: flow authorization (`workflow.security.flow-authorization.enabled` + definition `requiredRoles`/
  `requiredScopes`), `workflow.security.operations.required-roles`, `workflow.security.sensitive.reveal-roles`,
  `workflow.security.trust-forwarded-token` (§17).

---

## 3. Workflow definitions

Written in JSON or YAML (`.json`, `.yaml`, `.yml`); version-controlled and PR-reviewable.

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique workflow identifier |
| `name` | string | Human-readable name |
| `version` | integer | Version number |
| `description` | string | Optional |
| `paused` | boolean | Runtime flag (not authored in the .ec) — pause/resume. While true all processes are held and new instances (cron included) are born-`PAUSED` |
| `disabled` | boolean | Runtime flag (not authored in the .ec) — disable/enable. While true, no new instances (cron included); running ones continue |
| `archived` | boolean | Runtime flag (not authored in the .ec) — set by the git-import prune to hide a removed definition |
| `limitConcurrentExecutions` | boolean | Cap concurrent running instances |
| `maxConcurrentExecutions` | integer | Max instances (when limit enabled) |
| `enqueueOnLimit` | boolean | Queue new instances when the limit is reached |
| `cronExpression` | string | Spring cron; the engine starts a new instance at each occurrence (deterministic business keys, multi-pod safe) |
| `triggers` | array | `[{id?, destination, type, filter?, businessKey?, variables?}]` — or the same entries plus `workflow: <id>` in an `events.yaml` next to the definitions, never read as a definition, so a CDC stream or a partner's events can be wired to a workflow without changing it; triggers can also be created, edited, deleted and exported as `events.yaml` from the admin UI, `/workflow/api/triggers` or MCP (`createEventTrigger`…), a file entry with the same workflow and id taking one over on import — domain events (CloudEvents) that start an instance: a logical destination (`workflow.events.destinations.<name>.topic`), a type (`*` wildcards), a JEXL filter and templates over the event (`data.x`, `id`, `subject`, `key`…) for the business key (default derived from the event) and initial variables (default: `data`'s members); each event starts at most one process per trigger; ACTIVE definitions only, paused triggers skip; Kafka consumer in kafka mode, `POST /workflow/api/events/{destination}`, MCP `publishInboundEvent`, the embedded `InboundEventService`/Spring `InboundEvent`, and in embedded mode this engine's own PUBLISH_EVENTs |
| `schedules` | array | `[{id?, cron, zone?, variables?}]`: scheduled starts with an optional id (one schedule per id across the engine), their own IANA zone (default `workflow.time.zone`) and initial process variables; coexists with `cronExpression`. The same entries plus `workflow: <id>` can live in a `cron.yaml` next to the definitions (classpath, directory or repository import), never read as a definition; each import replaces its source's entries. Schedules can also be created, edited, paused, run now and exported as a `cron.yaml` entry from the admin UI (Workflow → Schedules), `/workflow/api/schedules` or the MCP schedule tools; on import a file entry with the same id takes such a schedule over (git overwrites), and an entry removed from its file is deleted |
| `defaultMaxStepExecutions` | integer | Default cap on successful runs per step, enforced when a `repeat`/`loopBack` would run a step again (the step fails); a step's `maxSuccessfulExecutions` overrides it; `0` = no cap of its own |
| `syncInvocation` | object | `{enabled, onFailure, onLockBusy, defaultDeadlineMs}` — lets a caller start an instance over HTTP and wait for its `REPLY`. `onFailure`: `REPLY_IMMEDIATELY` (default; answers FAILED with compensation IN_PROGRESS/NONE as soon as the process fails) or `REPLY_AFTER_COMPENSATION` (answers COMPENSATED/COMPENSATION_FAILED when the rollback ends). `onLockBusy`: `WAIT` (default, queue FIFO) or `FAIL` (409, no instance) |
| `processLock` | object | `{name, key}` — serialize every instance of this definition by a JEXL `key` (e.g. `bookingId`): instances resolving to the same key run one at a time, the rest wait FIFO. The process-level counterpart of a `LOCK`/`UNLOCK` section |
| `startPolicy` | object | `{key?, debounce?, throttle?: {limit, period, onLimit?: queue\|drop}, singleton?: skip\|replace}` — per-key flow control of starts (2.33): `key` a template over the start's variables (+`businessKey`, `workflowId`); applies to `POST /workflow/api/processes` (201 / 200 skipped / 202 held back / 429 dropped, header `EventConductor-Flow-Control`), sync invocation (409 `SINGLETON_RUNNING`, 429 `THROTTLED`; no definition-level `debounce` when `syncInvocation` is set) and triggers without their own. A trigger can carry its own `startPolicy` (key over the event); receipts then say `DEBOUNCED`/`THROTTLED`/`DROPPED`/`SKIPPED`/`REPLACED`. Order debounce → singleton → throttle; durable (`flow_control_key`/`flow_control_pending`, row lock per key), released by a scheduler pass. Not applied to cron, PROCESS children, raw upstream creations, the console |
| `businessKeyPolicy` | string | `unique` (default) \| `allowAfterFinished` \| `allowAfterFailed` \| `terminateRunning` — whether a start may take over a business key a run of this definition holds (2.33); every start path; never a key of another definition. A key then names a chain of runs; lookup by key answers the current holder (the UNIQUE `business_key`; earlier runs keep it in `released_business_key`) |
| `status` | string | `DRAFT` \| `ACTIVE` \| `DISABLED` \| `ARCHIVED` (see Definition status) |
| `tags` | string[] | Labels (`billing`, `team:payments`); metadata, no new version; Processes page filters by tag |
| `retention` | string \| object | `P30D`, or per terminal status `{completed, cancelled, error, compensated, compensationFailed}` (days-to-seconds ISO durations or `forever`), counted from the process end; a purge job deletes finished process trees (root + children) past it. Engine defaults `workflow.processes.retention` / `retention-by-status.<status>` are off unless set; metadata, no new version |
| `searchAttributes` | object | `{name: "${expr}"}` or `{name: {value: "${expr}", type: keyword\|text\|number\|date\|bool}}`, ≤ 20; recomputed on every save; filter with `query=` / `attr.<name>` (§6) |
| `sensitiveVariables` | string[] | Names or globs sealed with AES-256-GCM; needs `workflow.encryption.keys.<id>` (§17) |
| `updates` | array | Named, validated writes to a running process that answer with a result (§6b) |
| `requiredRoles` / `requiredScopes` | string[] | Enforced with `workflow.security.flow-authorization.enabled=true` |
| `steps` | array | The step definitions (**every step needs `id`, `type` and `name`**) |

### Runtime state

Definitions are authored as `.ec` files and imported — never edited in the UI. Two orthogonal runtime flags (outside the `.ec`): pause/resume (processes held; new instances born paused) and disable/enable (no new instances). A definition removed from its repo is archived by the import prune. Disabled and archived definitions start no new instances.

### Editor autocomplete

JSON:
```json
{ "$schema": "https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json", "id": "my-workflow" }
```
YAML (first line):
```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json
```

---

### Definition status

| Field | Values | Meaning |
|---|---|---|
| `status` | `ACTIVE` (default), `DISABLED`, `ARCHIVED` | Whether the workflow accepts new instances. Declaring it in the `.ec` is a **floor**: an operator can take a workflow out of service at runtime, but cannot put one into service that its own definition closes. `ARCHIVED` also hides it from the listing |
| `disabled`, `archived` | boolean | The older spelling, still read: `true` means the corresponding status |
| `paused` | boolean | A different axis: a paused workflow still accepts instances (born `PAUSED`); a disabled one accepts none. A workflow can be both |

### Which step type / pattern?

| You want | Use |
|---|---|
| automated work in your code | `ACTION` (+ a worker; `topic` routes it, `task` names a contract) |
| call a REST API without a worker | `HTTP_CALL` (`http: {connection, path, …}`; needs `worker-http`) |
| classify / extract / summarize with a model | `LLM_CALL` (`llm: {connection, prompt, …}`; needs `worker-llm`) |
| a model that picks and calls tools, bounded | `AGENT_CALL` (`agent: {goal, tools, maxSteps}`) |
| human approval / data entry | `USER_TASK` (`formId`, `assignment`, `escalation`; forms-engine) |
| pick ONE branch by data | `CHOICE` + guarded `preconditions` links, converge with `JOIN joinType: XOR` |
| run branches in parallel, then wait for all | `FORK` … `JOIN` (`preconditionStepIds` = branch ends) |
| run only when a condition holds | `preconditions: [{stepId, expression, onFalse}]` or `preconditionExpression` |
| undo completed work on failure (saga) | `compensable: true` + `compensationStepId` (compensation step has no preconditions) |
| wait a duration / until a date | `TIMER` (`duration`, `untilVariable`, or `until: {date, offset, at, zone}`) |
| finish by a date or route elsewhere | `deadline` / `timeout` + `onTimeoutStepId` |
| wait for a callback / external signal | `WAIT_FOR_MESSAGE` (`messageName` + `correlationExpression`; `onEvent` for a domain event) |
| send a request AND wait for its reply | `awaitReply` on `HTTP_CALL` / `PUBLISH_EVENT` (never request → separate WAIT_FOR_MESSAGE) |
| emit a domain event | `PUBLISH_EVENT` (`event: {destination, type, payload}`) |
| start on a domain event / webhook | definition `triggers` (or `events.yaml`) |
| start on a schedule | `schedules` / `cronExpression` (or `cron.yaml`) |
| caller waits for the answer over HTTP | `syncInvocation.enabled` + a `REPLY` step |
| change a running process with validation and an answer | definition `updates` (+ update-with-start) |
| same step once per list item | `forEach` (parallel/sequential, `output`) |
| poll until a condition | `repeat: {until, maxIterations, delay}` |
| redo a section (rework loop) | `loopBack: {to, while, maxIterations}` — never a precondition cycle |
| reusable sub-process | `PROCESS` (`childWorkflowDefinitionId`, `outputVariables`) |
| one process at a time per entity | `LOCK`/`UNLOCK` or definition `processLock` |
| business rule / decision table | `RULE` (`ruleId`) |
| debounce/throttle/singleton starts per key | definition or trigger `startPolicy` |
| endless / very long process | `CONTINUE_AS_NEW` + `businessKeyPolicy` |
| find processes by business data | `searchAttributes` + `query=` |
| delete finished processes | `retention` |
| protect PII | `sensitiveVariables` + `workflow.encryption.keys.*` |

Complete, validated examples of each pattern (saga with timer, human approval, HTTP integration, event-triggered
flow, request/response, forEach fan-out, polling, agent with tools, updates, long-lived flow control, child
process, sensitive data): https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/.claude/skills/eventconductor/reference/patterns.md

## 4. Step types

Steps run as a **pure dataflow**: a step starts when all its preconditions have `COMPLETED`
and its JEXL guard holds, concurrently with every other eligible step — array order is
irrelevant and the `parallel` flag is deprecated and ignored. **Roots rule:** a step with no
preconditions does not run; it must be the `START`, a `WAIT_FOR_MESSAGE` beginning a flow,
another step's `compensationStepId`, or a step used only as an agent's tool (rejected at load otherwise).
**Every step needs `id`, `type` and `name`** (schema).

### START — entry point
```json
{ "id": "start", "type": "START", "name": "Start" }
```
No worker: completes instantly at process creation, making its successors eligible. Must have
**no** preconditions. **At most one** `START` per definition (`A workflow can have at most one START
step`) — fan out after it with a `FORK`. To migrate an old definition, add one `START` and point the
old first steps at it.

### ACTION — dispatch a task to a worker
```json
{ "id": "charge", "type": "ACTION", "name": "Charge Payment", "topic": "payment-service",
  "preconditionStepId": "start",
  "timeout": "PT30S", "retries": 3, "compensable": true, "compensationStepId": "refund" }
```
- **Kafka mode:** `topic` is the destination a `TaskExecutionRequested` is published to; optional, defaulting to `downstream`.
- **Embedded mode:** `topic` is ignored — every task goes to the single `EmbeddedTaskExecutor` bean (there are no per-topic beans). May be omitted.
- **Task contract (optional):** set `"task": "<id>"` or `"<id>@<version>"` to reference a task contract (below). A bare id is pinned to the latest version at import; the contract's `topic` becomes the step's default (an explicit `topic` still wins); the pinned `<id>@<version>` is sent to the worker as its `taskId`.

#### Task contracts (`.ectask`)
The shape an ACTION commits to, so worker types can be generated from it. A contract lives in `definitions/tasks/*.ectask` (schema `urn:eventconductor:task-contract-schema:1`):
```yaml
id: confirm-booking
version: 1
group: booking          # one group = one generated worker module; an id belongs to exactly one group
topic: booking          # default destination for steps that use this task
input:
  bookingId: { type: string, required: true }
output:
  confirmationCode: { type: string }
errors:
  - { code: SOLD_OUT }  # each becomes a typed exception; code is a valid Java identifier
```
Types: `string`/`integer`/`number`/`boolean`/`date`/`datetime`/`object`/`array` (array carries `items`). Contracts are **versioned and append-only** — a backward-incompatible change is a new `version`, and nothing prunes an old one because an in-flight process may be pinned to it. Imported alongside workflows (classpath, `tasks.directory-import`, `tasks.git-import`) **ahead of** them so references resolve. The `workflow-maven-plugin` `validateTasks` goal checks the schema, one group per id, unique `id@version`, and that every referenced task exists. The `generate-worker-sources` goal of the Maven plugin generates `<Id>V<n>Input/Output` records and a `<Id>V<n>Task` interface (a `TaskHandler`) per contract version; implement it as a bean and run it with `worker-embedded` or `worker-kafka`.

### USER_TASK — pause for a human form
```json
{ "id": "approve", "type": "USER_TASK", "name": "Manager Approval",
  "formId": "expense-approval-form", "preconditionStepId": "submit", "timeout": 86400000 }
```
Creates a `FormExecution` in the forms engine. Requires `formId` and the `forms-engine` dependency.

Optional `assignment` — `{assignee?, candidateUsers?, candidateGroups?, priority? (0-100, default 50), dueDate?, followUpDate?}`, each a value or `${JEXL}` template rendered from the process (an iteration's item too) when the task is created; dates are an ISO-8601 duration from creation (`P3D`), an ISO date/date-time or a template. A task naming an assignee/candidates is visible and claimable only by them (groups = the caller's roles, `workflow.security.claims.roles`) and by task admins (`forms.tasks.admin-roles`, default `task-admin`); one naming nobody is anyone's; the form's `requiredRoles`/`requiredScopes` apply on top. Optional `escalation` — `[{after: PT48H, action?: reassign|notify|escalateTo, to?: {assignee?, candidateUsers?, candidateGroups?}, escalateTo?: <stepId>, publishEvent?: {destination, type, payload...}}]`, in firing order, each fires once while the task is open (engine timers on the deadline scan; the test kit's `advanceTime` skips to them): `reassign` replaces who it is for, `notify` only records/announces, `escalateTo` (last entry, not on forEach) closes the task and routes the process like `onTimeoutStepId`; `timeout` still wins; not combined with `deadline`. Inbox: `/forms/api/tasks?scope=all|mine|candidate|groups&overdue=&minPriority=&businessKey=&sort=priority|due|created`, `POST /forms/api/tasks/{id}/claim|release|complete|assign`; MCP `searchTasks`. Lifecycle CloudEvents `io.eventconductor.task.<created|assigned|claimed|released|completed|cancelled|escalated>` on `human-tasks`.

### RULE — evaluate a business rule
```json
{ "id": "apply-discount", "type": "RULE", "name": "Apply the discount rule",
  "ruleId": "high-value-order", "preconditionStepId": "register-order" }
```
Dispatches `taskId=evaluate-rule` with a `ruleId` variable; any app embedding `rule-runtime` evaluates the rule (expression or decision table, JEXL) against the process variables and its outputs are merged back as process variables. Requires `ruleId` and a rule with that id in the rule catalog (`rule-engine`). The runtime fetches rules per `rules.source`: `local`, `classpath`, `rest` or `grpc` (+ optional Kafka cache refresh).

### TIMER — durable wait
```json
{ "id": "wait-3-days", "type": "TIMER", "name": "Wait 3 days",
  "duration": "PT72H", "preconditionStepId": "send-reminder" }
```
Pauses the process without a worker: the step stays `PENDING` and the scheduler completes it once the due moment passes; the wait survives restarts. `duration` is ISO-8601 or ms; `untilVariable` names a process variable holding an ISO-8601 date/date-time and takes precedence. A misconfigured timer ends the step `ERROR` through the normal failure pipeline.

`until` waits for a **moment** from the process's data: `{"date": "${checkinDate}", "offset": "-P3D", "at": "09:00", "zone": "${hotelZone}", "ifPast": "fire"}` (or a date template alone). `offset` days are calendar arithmetic in `zone` (DST-safe); `zone` defaults to `workflow.time.zone`. `ifPast`: `fire` (default, complete at once) or `timeout` (end TIMEOUT → `onTimeoutStepId` or failure; no retries). Unlike `untilVariable`, an `until` moment **follows the variables** while the timer waits (recomputed on every variable change; a cleared date keeps the armed moment). Any waiting step (ACTION, USER_TASK, RULE, WAIT_FOR_MESSAGE, PROCESS, HTTP_CALL, LLM_CALL) can have a `deadline` (same moment, no `ifPast`): reaching it is a timeout that retries do not extend; with `timeout`, the earlier wins.

### WAIT_FOR_MESSAGE — wait for a message
```json
{ "id": "await-payment", "type": "WAIT_FOR_MESSAGE", "name": "Await payment confirmation",
  "messageName": "payment-confirmed", "correlationExpression": "businessKey",
  "preconditionStepId": "charge", "timeout": "PT24H" }
```
Waits until a `MessageReceived(messageName, correlationKey, variables)` arrives (REST / Kafka `upstream` as `"type":"message-received"` / MCP `sendMessage` / a `SEND_MESSAGE` step). **Both `messageName` and `correlationExpression` are required**: the correlation key is the value of the JEXL `correlationExpression` evaluated against process variables (fail-closed — an unevaluable expression matches nothing); write `"correlationExpression": "businessKey"` to correlate by business key. Message variables merge into the process. Unmatched messages are ignored, not buffered. `timeout`/`retries` keep their usual meaning. REST delivery: `POST /workflow/api/messages` with `{"messageName", "correlationKey", "variables": {..}}` responds 202. Since 2.27.0 `/workflow/api/**` refuses anonymous requests (401): send `X-Api-Key` matching `workflow.message-api.api-key`, or authenticate through the app's Spring Security; only `workflow.message-api.allow-anonymous=true` opens it.

**Waiting for a domain event:** add `onEvent: {destination, type, correlationKey?, filter?, variables?}` — a CloudEvent of that type on that destination becomes this step's message (`messageName` and `correlationExpression` stay required); `correlationKey` is a template over the event (`${data.bookingId}`; default the record key, else the subject), `variables` templates (default `data`'s members). The same event (source + id) is correlated at most once per message and key.

Previously named `MESSAGE`: the old name is a deserialization alias (persisted in-flight state and old definition files keep loading, and legacy steps without a `correlationExpression` fall back to the business key), but new/reimported definitions must use `WAIT_FOR_MESSAGE` with an explicit `correlationExpression`.

### SEND_MESSAGE — emit a message (fire-and-forget)
```json
{ "id": "notify-payment", "type": "SEND_MESSAGE", "name": "Notify payment",
  "messageName": "payment-confirmed", "correlationExpression": "orderId",
  "messageVariables": ["paymentId", "amount"], "preconditionStepId": "charge" }
```
The throw side: no worker involved. On start the engine evaluates `correlationExpression` (same JEXL context as preconditions), emits `MessageReceived(messageName, correlationKey, variables)` through the outbox and completes the step immediately — in-engine process-to-process signaling without an ACTION step + worker. **Both `messageName` and `correlationExpression` are required.** `messageVariables` lists the process-variable names the message carries; empty/absent = none (process state is never sent implicitly). Fire-and-forget: delivery is not acknowledged, and a message matching no waiting process is discarded (not buffered). Failure is **loud**: missing `messageName`/`correlationExpression` or a correlation key that cannot be evaluated puts the step in `ERROR` (normal retry/compensation pipeline) — deliberately NOT the silent fail-closed of precondition guards.

### PROCESS — run a child workflow
```json
{ "id": "run-kyc", "type": "PROCESS", "name": "Run KYC",
  "childWorkflowDefinitionId": "kyc-workflow", "outputVariables": ["kycResult"],
  "preconditionStepId": "create-customer", "timeout": "PT1H" }
```
No worker: when the step starts, the engine creates a child process of
`childWorkflowDefinitionId` (which must differ from the workflow's own id — direct
self-recursion is rejected) carrying **all** parent variables, with the deterministic business
key `parent:<stepExecutionId>` (idempotent — redelivered creation events are deduped). The
parent step waits `PENDING`. On child `COMPLETED`, the parent step completes and copies back
**only** the child variables named in `outputVariables` (empty/absent = none). On child
`ERROR` or `CANCELLED`, the parent step goes `ERROR` (normal retry/compensation pipeline).
`timeout` bounds the wait. Cancellation propagates down: a parent step ending `CANCELLED`,
`ERROR` or `TIMEOUT` (retries exhausted) cancels a still-running child, cascading to
grandchildren; while retries remain the child keeps running (a retried step re-attaches to it
via the deterministic business key).

### FORK / JOIN / CHOICE — gateways
```yaml
- { id: fork,  type: FORK,   name: Notify, preconditionStepId: process-order }
- { id: email, type: ACTION, name: Email,  topic: email-service, preconditionStepId: fork }
- { id: sms,   type: ACTION, name: SMS,    topic: sms-service,   preconditionStepId: fork }
- { id: join,  type: JOIN,   name: Sent,   preconditionStepIds: [email, sms] }
```
Both are no-worker nodes that complete instantly. FORK is the explicit fan-out: every step
preconditioned on it starts concurrently (any step type fans out the same way — FORK just
makes it visible). JOIN is the barrier/converge point: its **multiple preconditions**
(`preconditionStepIds`) must ALL complete before it runs (`joinType: AND`, the default) — or, with
`joinType: XOR`, the first one to complete. Do not use `parallel: true` — it is deprecated and ignored.

**CHOICE** is the exclusive split: it takes exactly ONE successor — the first whose link guard holds,
evaluated from the longest guard expression to the shortest; a link with no guard is the default (tried
last). The pick latches. Converge its branches with a `JOIN joinType: XOR` (an `AND` join would wait for
branches that never run, and the process would complete implicitly, cancelling what follows). A CHOICE
with no default takes no branch when every guard is false (the plugin warns).
```yaml
- { id: decide, type: CHOICE, name: "Approved?", preconditionStepId: approve }
- id: pay
  type: ACTION
  name: Pay
  topic: payments
  preconditions: [ { stepId: decide, expression: "decision == 'APPROVE'" } ]
- id: reject
  type: ACTION
  name: Notify rejection
  topic: notifications
  preconditions: [ { stepId: decide } ]          # default branch
- { id: done, type: JOIN, name: Done, joinType: XOR, preconditionStepIds: [pay, reject] }
```

### LOCK / UNLOCK — serialize a critical section by a key
```yaml
- { id: lock,   type: LOCK,   name: Take booking lock,    lockName: booking, lockKey: bookingId, preconditionStepId: start }
- { id: work,   type: ACTION, name: Work on the booking,  topic: booking, preconditionStepId: lock }
- { id: unlock, type: UNLOCK, name: Release booking lock, lockName: booking, lockKey: bookingId, preconditionStepId: work }
```
No worker. `LOCK` takes the named per-key lock — `lockName` (the domain, defaults to the definition id) plus `lockKey` (a **required** JEXL expression over the process variables, e.g. `bookingId`). If it is free the step completes and the flow proceeds; if another process holds it, the step parks in the `WAITING_ON_LOCK` status and is admitted in **arrival order (FIFO)** when the lock frees. A parked step is active work — the process neither completes nor errors around it. `UNLOCK` releases it and admits the next waiter. Everything between them is serialized against every process sharing that key; the lock is not held during work outside the section. Released by `UNLOCK`, on the process reaching a terminal state (all held locks freed), or — the crash backstop — by a lease reaper (`workflow.lock.lease-ms`, default 15 min). For serializing a **whole** instance instead of a section, set the definition-level `processLock` (a Top-level field, §3).

### REPLY — answer a synchronous caller
```json
{ "id": "confirm", "type": "REPLY", "name": "Booking confirmed",
  "replyVariables": ["bookingId", "confirmation"], "preconditionStepId": "charge" }
```
No worker. Records the process's reply — `replyVariables` (a JSON object of those variables) or `replyExpression` (a JEXL value, e.g. `{'bookingId': bookingId, 'status': 'CONFIRMED'}`); neither = `{}` — on the process, in the same transaction, and completes; the flow carries on (an early REPLY answers while the saga continues). **At most one per run**: two REPLY steps must be on different branches of a dominating `CHOICE` (or a step's normal vs timeout route); validated on import and by the Maven plugin. Not allowed as a compensation step or injected by DYNAMIC. A reply that cannot be computed, or exceeds `workflow.sync.max-reply-bytes`, fails the step. Used with the definition-level `syncInvocation` (§3) and the invocation API: `POST /workflow/api/definitions/{id}/invocations` (`Idempotency-Key` required, `Prefer: wait=N`) → 200 reply / 202 + `Location` / 502 failure outcome; `Accept: text/event-stream` for progress events. See guides/synchronous-invocation.

### PUBLISH_EVENT — publish a domain event
```json
{ "id": "announce", "type": "PUBLISH_EVENT", "name": "Booking confirmed", "preconditionStepId": "confirm",
  "event": { "destination": "bookings", "type": "com.acme.booking.confirmed", "key": "${bookingId}",
             "payload": { "bookingId": "${bookingId}", "total": "${total * 1}" } } }
```
No worker. The event (payload from a structured `payload` template, a text `payloadTemplate` or `payloadVariables`; key defaults to the business key, else the process id) is written to the outbox with the step's completion — published if and only if the step completed; at-least-once, `id` = the step execution id. Kafka: to the topic of `workflow.events.destinations.<destination>.topic` as a CloudEvent (binary: `ce_*` headers; or structured / plain). Embedded: `ExternalEventPublisher` (default Spring `ExternalEventPublished`). A definition never names a topic.

**`awaitReply: {messageName, correlationExpression, onEvent?}`** (HTTP_CALL and PUBLISH_EVENT, since 2.28.0) makes the request step wait for its reply — asynchronous request/response in one step: the wait is armed in the transaction that sends the request, so a fast reply is never lost (a `WAIT_FOR_MESSAGE` placed *after* the request can lose it); the step completes when the reply is correlated; `timeout`/`deadline`/`retries`/`onTimeoutStepId` bound the whole exchange, a retry re-sends and re-arms (a retried PUBLISH_EVENT gets id `<step execution id>-<attempt>`). HTTP_CALL: a 2xx before the reply is recorded (`output` mapped) and the step keeps waiting; a reply before the response completes the step and the late response is ignored; a failed request fails the step as usual.

### HTTP_CALL — call an HTTP endpoint
```json
{ "id": "charge", "type": "HTTP_CALL", "name": "Charge", "preconditionStepId": "book", "retries": 2,
  "http": { "connection": "payments", "method": "POST", "path": "/charges/${bookingId}",
            "body": { "amount": "${amount * 1}" }, "output": { "chargeId": "body.id" } } }
```
The engine renders the request (templates in url/path, query, headers, body) and dispatches the built-in task `http-call@1` (library `worker-http`: embedded in the engine's pod, or topic `http-calls` served by `http-worker-standalone-app`). `successStatus` (default 2xx) completes and maps `output` (JEXL over `{status, headers, body}`) into variables; otherwise `HTTP_<status>` / `HTTP_IO`, retried only if `retryOn` (default `[5xx, io]`) covers it — a 4xx is final and compensation runs. `Idempotency-Key` = step execution id. Auth: named profile (`workflow.http.auth.<name>`) or inline with `${secret:NAME}` credentials only; absolute URLs subject to `workflow.http.allowed-hosts` and never internal addresses. Credentials are bound to hosts: a named profile reaches an absolute URL only when its host is in the profile's `hosts` (or `allowed-hosts` is set), else only through a connection; inline `${secret:…}` credentials reach an absolute URL only when `allowed-hosts` is set; otherwise `HTTP_FORBIDDEN_AUTH` (`workflow.http.credentials-to-any-host=true` lifts it).

### LLM_CALL — ask a language model
```json
{ "id": "triage", "type": "LLM_CALL", "name": "Triage", "preconditionStepId": "log", "retries": 2,
  "llm": { "connection": "claude", "system": "You triage complaints.", "prompt": "${guestName} wrote: ${complaint}",
           "responseSchema": { "type": "object", "required": ["category"] },
           "output": { "category": "json.category" }, "usageVariable": "triageUsage" } }
```
The engine renders `system` and `prompt` (text templates) and dispatches the built-in task `llm-call@1` (library `worker-llm` on Spring AI, with `spring-ai-anthropic` and/or `spring-ai-openai`: embedded in the engine's pod, or topic `llm-calls` served by `llm-worker-standalone-app`). Connections are configuration: `workflow.llm.connections.<name>` = `provider` (`anthropic` | `openai`), `model`, `api-key`, `base-url`, `temperature`, `max-tokens` (4096), `timeout` (PT60S), `structured-output` (`prompt` | `native`). A step may override `model`, `temperature`, `maxTokens`. With `responseSchema` the answer must be one JSON document valid against it (`LLM_INVALID_OUTPUT` otherwise, retried). Answer → `outputVariable` (text / JSON) and `output` (JEXL over `{text, json, usage, model, finishReason}`); token usage → `usageVariable`. Provider 4xx → `LLM_<status>`, final; 408/409/429/5xx and `LLM_IO` retried. Metrics: `eventconductor.llm.call{workflow,step,connection,model,outcome}`, `eventconductor.llm.tokens{workflow,step,connection,model,type}`.

### AGENT_CALL — a model that uses tools
```yaml
- id: triage
  type: AGENT_CALL
  name: Triage the ticket
  preconditionStepId: start
  timeout: PT60S          # per model call
  retries: 2              # per model call
  agent:
    connection: claude
    system: You are a support agent. Use the tools; never guess an order.
    goal: "Customer ${customerId} wrote: ${message}"
    tools:
      - { name: lookupOrder, description: Look up an order, http: { connection: orders, method: GET, path: "/orders/${orderId}" },
          inputSchema: { type: object, required: [orderId], properties: { orderId: { type: string } } } }
      - { name: openRefund, description: Open a refund, process: refund-flow, outputVariables: [refundId],
          approval: { formId: approve-refund, candidateGroups: [support-leads] } }
      - { name: notifyCustomer, description: Email the customer, step: notify-customer }
    output: { schema: { type: object, required: [decision] }, mapping: { decision: "json.decision" } }
    maxSteps: 6
    maxTokens: 50000
    maxDuration: PT30M
```
The agent's execution is a container (the forEach model). Each model turn is a child execution dispatched to the built-in `agent-turn@1` task (`worker-llm`, same connections as `LLM_CALL`, topic `llm-calls`) with the conversation so far and the tool specs; the provider never runs a tool. Each call the model asks for is validated (declared tool, arguments valid against `inputSchema`; otherwise the model is told why and nothing runs) and becomes a child execution of the tool's step: a `step:` tool runs that step of the definition (it must have no preconditions and be nobody's precondition/compensation/on-timeout target — its id is the child's step id), an `http:` tool an HTTP_CALL (allowed hosts, host-bound credentials), a `process:` tool a child process (`outputVariables` = result), a `task:` tool an ACTION; synthesized tools' step id is `<agent>.<tool>`. Arguments are laid over the variables for that call only; a tool never writes process variables — what it reports is the model's tool result. `approval` creates a USER_TASK (`<agent>.<tool>.approval`) first; its `approved` field decides. `toolConcurrency` (default 1) bounds a turn's parallel calls. Final answer → checked against `output.schema` (`LLM_INVALID_OUTPUT`, retried), mapped by `output.mapping` (JEXL over `{text, json, usage, turns, toolCalls}`) / `output.variable`. Budgets fail the step, final: `AGENT_MAX_STEPS`, `AGENT_MAX_TOKENS`, `AGENT_TIMEOUT` (ends TIMEOUT); `AGENT_MODEL_FAILED`; `AGENT_TOOL_FAILED` (or, with `onToolError: report`, the model is told). Completed calls of compensable `step:` tools are compensated per call, newest first. No in-memory state: the loop is rebuilt from the persisted children on every step-over, so it resumes after a crash. The transcript (redacted, ≤ 64 KB) is on the agent's execution: Agent tab, REST `agent` summary, MCP `getAgentTranscript`. Test kit: `runtime.whenAgent("triage").thenCallTool("lookupOrder", Map.of("orderId","O-1")).thenAnswer("{...}")`, tools answered by `whenStep` / `whenHttpCall("triage.lookupOrder")` / `whenTask` / `completeUserTask`.

### Loops and multi-instance — `forEach`, `repeat`, `loopBack`
The precondition graph stays acyclic (a cycle is still an error); repetition is declared on a step:
```yaml
- id: reserve            # once per item
  type: ACTION
  name: Reserve a room
  topic: booking
  preconditionStepId: start
  compensable: true
  compensationStepId: release          # once per COMPLETED iteration, newest first
  forEach: { items: rooms, as: room, indexAs: i, mode: parallel, maxConcurrency: 3,
             output: reservations, outputElement: reservationId, completion: all }
- id: poll               # until a condition
  type: HTTP_CALL
  name: Poll the payment
  preconditionStepId: reserve
  http: { connection: pay, path: "/p/${paymentId}", output: { paymentStatus: "body.status" } }
  repeat: { until: "paymentStatus == 'SETTLED'", maxIterations: 20, delay: PT30S }
- id: rework             # back-edge, on a CHOICE branch (no `while` = always)
  type: ACTION
  name: Ask for rework
  topic: notify
  preconditions: [ { stepId: decide, expression: "verdict == 'REWORK'" } ]
  loopBack: { to: draft, maxIterations: 5 }
```
- **forEach**: `items` (JEXL; a variable holding a JSON array/object is read as structure; evaluated once) → one child step execution per item with its own task id, retries and timeout. The iteration sees the process vars with `as` (default `item`) and `indexAs` (default `index`) overlaid; workers get objects as JSON text, HTTP/LLM templates get them typed (`${room.number}`). What an iteration reports is NOT written to the process; `output` collects one element per item in item order (`null` if it did not complete), `outputElement` = JEXL over what it reported (default: all of it as an object). `completion`: `all` | `any` | JEXL over vars + `completedIterations`/`totalIterations`/`activeIterations` (+ the output so far) — the rest are cancelled. One iteration failing for good fails the step (rest cancelled, non-retryable); retrying it re-runs only failed/cancelled iterations. Empty list → completes at once (`output` = `[]`). Cap `workflow.foreach.max-items` (1000).
- **repeat**: `until` evaluated once per completed run over the vars + `iteration` (1-based); false → a new execution after `delay`.
- **loopBack**: when the step completes and `while` (default always; vars + `iteration`) holds, every step on a path from `to` to it gets a fresh execution — JOIN/XOR/CHOICE latches reset, vars carry over, in-flight ones in the section are cancelled. `to` must be upstream; no REPLY/END in the section; loops nested or apart.
- Decisions latch (evaluated once per completion). Caps — `maxIterations` (required), `maxSuccessfulExecutions`/`defaultMaxStepExecutions`, `workflow.loops.max-iterations` (1000) — fail the step finally when exceeded.
- Compensation runs once per completed iteration/pass, newest first, each seeing that run's item/index and reported variables.
- Storage: passes are step executions with `iteration` > 0 (earlier ones `superseded`); iterations have `parentExecutionId` + `itemIndex` (migration V47).

### CONTINUE_AS_NEW — hand over to a fresh run
```yaml
- id: next-month
  type: CONTINUE_AS_NEW
  name: Next month
  preconditionStepId: bill
  continueAsNew: { version: latest, variables: { customerId: "${customerId}", month: "${month + 1}" } }
```
Ends the run as END does (COMPLETED, outcome `continued`, other branches cancelled) and, in the same transaction, creates a new run of the same definition (`version: same` = this run's snapshot, default; `latest`) with the same business key and the carried variables (absent = all; list of names; map of templates). Linked `continuedFrom`/`continuedAs` (REST, MCP, console). A child continuing keeps the parent step waiting until the last run ends. Nothing may follow it; no forEach/repeat/loopBack. Keeps history bounded (with loops).

### END — complete the process
```json
{ "id": "end", "type": "END", "name": "Done", "preconditionStepId": "last-step" }
```
One per workflow (recommended; the process also completes when nothing is runnable). Transitions the process to `COMPLETED`. With parallel branches, precede it with a JOIN.

### Common step fields

`priority` (0–100, default 50; ACTION, HTTP_CALL, LLM_CALL, RULE, AGENT_CALL): ordering of the HTTP worker queue, the embedded pool (`workflow.embedded.worker-threads > 0`) and the rate-limit queue; Kafka keeps topic order. Worker rate limits per topic: `workflow.workers.rate-limits.<topic>.{permits, period}` — over it the task waits PENDING (timeout not running), released priority first then round-robin per process.

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique within the workflow |
| `type` | enum | — | `START`/`ACTION`/`USER_TASK`/`RULE`/`TIMER`/`WAIT_FOR_MESSAGE`/`SEND_MESSAGE`/`PROCESS`/`FORK`/`JOIN`/`CHOICE`/`LOCK`/`UNLOCK`/`REPLY`/`PUBLISH_EVENT`/`HTTP_CALL`/`LLM_CALL`/`AGENT_CALL`/`DYNAMIC`/`CONTINUE_AS_NEW`/`END` |
| `name` | string | — | Human-readable; **required by the schema** |
| `description` | string | — | Optional |
| `preconditionStepId` | string | — | Single step that must complete first |
| `preconditionStepIds` | string[] | — | Steps that must ALL complete first; wins over the singular form when non-empty |
| `preconditions` | object[] | — | `{stepId, expression?, onFalse?}` per incoming link: the condition belongs to that route in, not to the step. Wins over both spellings above. A link whose `expression` is falsy is **not satisfied**; `onFalse` says what that means — `WAIT` (default) holds the step (it is not skipped, and the process does not finish around it), `DISCARD` makes it a branch not taken (skipped, and the process may finish and cancel it) |
| `preconditionExpression` | string | — | JEXL guard on the step, whatever route reached it; while falsy the step is never run (stays `CREATED`, → `CANCELLED` when `END` fires). The older spelling: folded into every link of the step as an `onFalse: DISCARD` guard, so prefer `preconditions[].expression` |
| `parallel` | boolean | `false` | **Deprecated and ignored** (kept for deserialization of old files) |
| `topic` | string | — | Worker destination (ACTION, Kafka mode); defaults to the task contract's topic when `task` is set |
| `task` | string | — | Task contract this ACTION runs: `<id>` or `<id>@<version>`. A bare id is pinned to the latest version at import; dispatched to the worker as its `taskId` |
| `formId` | string | — | Form to render (USER_TASK) |
| `ruleId` | string | — | Rule to evaluate (RULE) |
| `childWorkflowDefinitionId` | string | — | Child workflow (PROCESS; must differ from the workflow's own id) |
| `outputVariables` | string[] | — | Child variables copied back to the parent on completion (PROCESS); empty/absent = none |
| `duration` | duration | `0` | Wait length (TIMER); ISO-8601 or ms |
| `untilVariable` | string | — | Variable holding an ISO-8601 date/date-time (TIMER); wins over `duration` |
| `until` | moment | — | TIMER: `{date, offset, at, zone, ifPast}` or a date template; follows the variables; exclusive with `duration`/`untilVariable` |
| `deadline` | moment | — | Waiting steps: `{date, offset, at, zone}` by which the step must finish; a timeout retries do not extend; earlier of it and `timeout` wins |
| `messageName` | string | — | Message to wait for / emit (WAIT_FOR_MESSAGE / SEND_MESSAGE; **required** for both) |
| `correlationExpression` | string | — | JEXL producing the correlation key (WAIT_FOR_MESSAGE / SEND_MESSAGE; **required** for both — use `businessKey` for the business key) |
| `messageVariables` | string[] | — | Process-variable names the outgoing message carries (SEND_MESSAGE); empty/absent = none |
| `timeout` | duration | `0` | ISO-8601 (`PT30S`, `PT5M`, `PT1H30M`) or ms integer; `0` = none |
| `retries` | integer | `0` | Auto-retry attempts on ERROR/TIMEOUT |
| `compensable` | boolean | `false` | Enable saga compensation on failure |
| `compensationStepId` | string | — | Compensation step (needs `compensable: true`) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on successful runs of this step per process; enforced when a loop would run it again (the step fails); `0` inherits `defaultMaxStepExecutions` |
| `forEach` | object | — | Multi-instance (ACTION, USER_TASK, PROCESS, HTTP_CALL, LLM_CALL, RULE): `{items, as?, indexAs?, mode?, maxConcurrency?, output?, outputElement?, completion?}` — see "Loops and multi-instance" |
| `repeat` | object | — | Same types: `{until, maxIterations, delay?}` — run the step again until `until` holds |
| `loopBack` | object | — | Any step but START/END/REPLY: `{to, while?, maxIterations, delay?}` — run the section from `to` to here again |
| `lockName` | string | — | Lock domain (LOCK/UNLOCK); defaults to the definition id |
| `lockKey` | string | — | JEXL expression giving the key to serialize on (LOCK/UNLOCK; **required** on those) |
| `replyVariables` | string[] | — | REPLY: variables the reply object is made of |
| `replyExpression` | string | — | REPLY: JEXL expression whose value is the reply (exclusive with `replyVariables` and `replyTemplate`) |
| `replyTemplate` | any | — | REPLY: the reply as a payload template (JSON/YAML with `${…}` leaves) |
| `event` | object | — | PUBLISH_EVENT: `{destination, type, key?, payload \| payloadTemplate \| payloadVariables, format?}` |
| `awaitReply` | object | — | HTTP_CALL / PUBLISH_EVENT: `{messageName, correlationExpression, onEvent?}` — wait for the reply to the request in the same step, armed before the request leaves |
| `http` | object | — | HTTP_CALL: `{connection + path \| url, method, query, headers, body \| bodyTemplate, successStatus, output, auth, retryOn}` |
| `llm` | object | — | LLM_CALL: `{connection, model, system, prompt, temperature, maxTokens, responseSchema, output, outputVariable, usageVariable, allowSensitiveVariables}` |
| `agent` | object | — | AGENT_CALL: `{connection, goal, tools, maxSteps, system?, model?, output?, maxTokens?, maxDuration?, toolConcurrency?, onToolError?, allowSensitiveVariables?}` |
| `continueAsNew` | object | — | CONTINUE_AS_NEW: `{version?: same\|latest, variables?}` |
| `onTimeoutStepId` | string | — | On timeout route to this step instead of failing (guard the target with `preconditions: [{stepId: <this>, expression: "false"}]`) |
| `onEvent` | object | — | WAIT_FOR_MESSAGE: `{destination, type, correlationKey?, filter?, variables?}` |
| `joinType` | enum | `AND` | JOIN: `AND` \| `XOR` |
| `assignment` / `escalation` | object / array | — | USER_TASK: who may claim it, priority, due dates / timed reassign, notify, escalateTo |
| `priority` | integer | `50` | 0–100; ACTION, HTTP_CALL, LLM_CALL, RULE, AGENT_CALL |
| `sensitiveOutputs` | boolean | `false` | PROCESS: its `outputVariables` are sensitive |
| `rollbackable` | boolean | — | Old spelling of `compensable` |

---

## 5. Variables & JEXL

- Variables are `(name, value)` string pairs, passed at process creation and merged with each worker's output variables (same name overwrites).
- Available to every subsequent step and to JEXL `preconditionExpression`s.
- JEXL numeric comparisons run on the string representation: `"amount > 1000"`, `"approved == 'true'"`.

```json
{ "id": "review", "type": "USER_TASK", "name": "Review", "formId": "review-form",
  "preconditionStepId": "check", "preconditionExpression": "amount > 1000" }
```

---

## 6. Starting, cancelling & querying a process

### Programmatically (any mode)
```java
@Autowired ProcessUpstreamEventUseCase processUpstreamEventUseCase;

// start
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested("my-workflow", "order-123",
        List.of(new Variable("orderId", "123"), new Variable("amount", "99.90")))));

// cancel — running/pending steps → CANCELLED, process → CANCELLED
@Autowired CancelProcessUseCase cancelProcessUseCase;
cancelProcessUseCase.handle(new CancelProcessCommand(processId));

// retry the failed steps of an ERROR/CANCELLED process; restart = from the top
@Autowired RetryProcessUseCase retryProcessUseCase;
retryProcessUseCase.handle(new RetryProcessCommand(processId));
@Autowired RestartProcessUseCase restartProcessUseCase;
restartProcessUseCase.handle(new RestartProcessCommand(processId));

// pause a PENDING/RUNNING process → PAUSED; resume → back to RUNNING
@Autowired PauseProcessUseCase pauseProcessUseCase;
@Autowired ResumeProcessUseCase resumeProcessUseCase;
pauseProcessUseCase.handle(new PauseProcessCommand(processId));
resumeProcessUseCase.handle(new ResumeProcessCommand(processId));

// pause/resume a whole definition by id (bulk + born-paused new instances)
@Autowired PauseWorkflowUseCase pauseWorkflowUseCase;
@Autowired ResumeWorkflowUseCase resumeWorkflowUseCase;
pauseWorkflowUseCase.handle(workflowDefinitionId);
resumeWorkflowUseCase.handle(workflowDefinitionId);
```

Pause semantics: pause holds the frontier, not in-flight work — running workers finish and
their reports are accepted (steps complete, variables merge), messages still complete
`WAIT_FOR_MESSAGE` steps, but successors do not start until resume. Timer/timeout clocks
freeze (the schedulers skip paused processes; on resume every non-terminal started step's
`startedAt` is shifted forward by the pause duration) and blocking-error handling is
deferred. Pausing a definition sets its runtime `paused` flag and pauses all its
PENDING/RUNNING processes; new instances — cron included — are still created, **born
PAUSED**, and start on resume.

### Via Kafka (mode: kafka)
Send to the `upstream` topic:
```json
{ "type": "process-creation-requested", "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [ { "name": "orderId", "value": "123" }, { "name": "amount", "value": "99.90" } ] }
```
`businessKey` is an optional human-readable identifier for later lookup.

### Over REST

Every `/workflow/api/**` call needs `X-Api-Key` (= `workflow.message-api.api-key`), the app's Spring
Security credentials, or `workflow.message-api.allow-anonymous=true` (§2).

```bash
curl -X POST http://localhost:8080/workflow/api/processes -H 'X-Api-Key: dev-key' \
     -H 'Content-Type: application/json' \
     -d '{"definitionId":"booking-saga","businessKey":"B-1","variables":{"amount":120}}'
```

Processes over REST: `/workflow/api/processes` — `POST` start `{definitionId, businessKey?, variables?}` (201 + `Location`; same key and definition → 200 with the existing process; a key of another definition → 409; disabled definition → 409; unknown → 404), `GET` list with `definitionId`, `tag`, `status` (repeat or comma-separated), `from`/`to` (ISO date-time or date), `businessKey` (contains), `q`, `page`/`size` (≤500) → `{content, page, size, totalElements, totalPages}`, `GET /by-key?businessKey=`, `GET /{id}` (with variables), `/{id}/steps`, `/{id}/variables`, `/{id}/logs`, and commands `POST /{id}/cancel|pause|resume|retry|restart`, `/{id}/steps/{stepExecutionId}/retry` → 202 (carried out by the owning node; 404 unknown; 409 when the status does not allow it, by the use case's own rule). Same `X-Api-Key` rule as the rest of `/workflow/api/**`; with `workflow.security.flow-authorization.enabled` the definition's `requiredScopes`/`requiredRoles` gate start, reads and commands (403); errors are problem+json. A `startPolicy` can make `POST` answer 202 (held back) or 429 (dropped). OpenAPI 3.1 at `/openapi/eventconductor-api.yaml` (docs site), served live as `/v3/api-docs/eventconductor` when the app adds springdoc (`springdoc-openapi-starter-webmvc-api`) and sets `workflow.api.openapi.enabled=true`.

Search: `GET /workflow/api/processes?query=customerId = "C-9" AND amount > 100` (URL-encoded) or
`attr.<name>=v`, `attr.<name>.gt|gte|lt|lte=v` over the definition's `searchAttributes`. The query
language is `name op value (AND …)*`, ops `= > >= < <=`, values bare or quoted; no OR, parentheses or
negation; ≤ 10 conditions.

### 6b. Messages, updates, operations

- **Messages** — `POST /workflow/api/messages {messageName, correlationKey, variables}` → 202; resumes a
  waiting `WAIT_FOR_MESSAGE`/`awaitReply`; dropped when nothing waits.
- **Updates** — a definition's `updates: [{name, accepts (JSON Schema), acceptedWhile: [stepIds],
  validate: [{expression, message}], set: {var: JEXL}, completes: <WAIT_FOR_MESSAGE id>, reply | replyTemplate,
  sensitive: [fields]}]` are validated writes answered with a result, any number of times:
  `POST /workflow/api/processes/{id}/updates/{name}` (or `/processes/by-key/updates/{name}?businessKey=`),
  optional `Idempotency-Key`, `Prefer: wait=N` → 200 `{status: COMPLETED, result}` / 202 + Location /
  400 payload / 404 / 409 not accepting / 422 refused / 500 expression failed (nothing changed).
  `POST /workflow/api/definitions/{id}/update-with-start {businessKey, variables, update, payload}` starts the
  process when the key is free (the update is its first act) or updates it (follows `businessKeyPolicy`).
  MCP `updateProcess`, `updateWithStart`, `listProcessUpdates`.
  ```yaml
  updates:
    - name: changeAddress
      accepts: { type: object, required: [address], properties: { address: { type: string, minLength: 5 } } }
      acceptedWhile: [awaitShipping]
      validate:
        - { expression: "payload.address != address", message: That is already the shipping address. }
      set: { address: payload.address }
      reply: "{'address': address}"
  ```
- **Manual operations** (audited, `reason` required, roles `workflow.security.operations.required-roles`):
  `POST /processes/{id}/variables {set, remove, reason}`, `POST /processes/{id}/steps/{stepExecutionId}/complete
  {variables, reason}`, `POST /processes/{id}/retry-from/{stepId} {reason}`; MCP `setProcessVariables`,
  `completeStepManually`, `retryProcessFromStep`. Refused on finished processes and during a rollback.
- **Incidents** — failed steps grouped by definition + step + error family: `GET /workflow/api/incidents`,
  `GET /incidents/{key}/processes`, `POST /incidents/{key}/retry {reason}` (a RETRY batch); MCP
  `listIncidents`, `getIncidentProcesses`, `retryIncident`.
- **Batch operations** — `POST /workflow/api/batch-operations {type, filter, variables?, remove?, stepId?, reason?,
  targetVersion?, fromVersion?, stepMapping?}`; types `CANCEL`, `RETRY`, `RESTART`, `PAUSE`, `RESUME`,
  `SET_VARIABLES` (reason), `RETRY_FROM_STEP` (stepId, reason), `MIGRATE` (targetVersion, stepMapping,
  variables, reason); non-empty `filter {definitionId, tag, status[], from, to, businessKey, q, onlyErrors,
  query, attributes}`. Runs in the background on one node, throttled (`workflow.batch-operations.*`),
  resumable, cancellable (`POST /batch-operations/{id}/cancel`); each process authorized and status-checked.
  MCP `createBatchOperation`, `migrateProcessesInBatch`, `listBatchOperations`, `getBatchOperation`, `cancelBatchOperation`.
- **Migration** — move running processes to another version: `POST /processes/{id}/migrate/dry-run
  {targetVersion?, stepMapping?, variableMapping?}` (plan + problems) and `POST /processes/{id}/migrate {…, reason}`;
  `MigrateProcessUseCase`; batch `MIGRATE`; MCP `dryRunProcessMigration`, `migrateProcess`. In CI:
  `mvn eventconductor:check-migration -Dfrom=<git ref|path|current> [-Dto=…] [-Dmapping=file] [-Dworkflow=id]`
  fails when a step a process can wait in has no compatible counterpart. Memory persistence has no
  version history (migrate to the current version only).
- **Reveal** a sensitive value: `POST /processes/{id}/variables/{name}/reveal {reason}` (§17).

### Query
```java
@Autowired ProcessRepository processRepository;
Optional<Process> p  = processRepository.findById(processId);
Optional<Process> p2 = processRepository.findByBusinessKey("order-123");
long running = processRepository.countByStatus(ProcessStatus.RUNNING);
```
There is no `findByStatus`; filter `findAll()` (or use `find(...)`) for status-based queries.

---

## 7. Implementing workers

A worker receives a `TaskExecutionRequested`, performs business logic, and reports a `TaskStatusChanged` / `UpdateStepExecutionCommand` with the outcome and output variables. Workers are **stateless**; the orchestrator owns retries, timeouts, and error tracking.

```java
record TaskExecutionRequested(
    String taskExecutionId,       // <-- use THIS to report back
    String processId,
    String workflowDefinitionId,
    String stepId,
    String taskId,
    List<Variable> variables,     // process variables at this point (incl. prior outputs)
    Integer attempt,              // echo it back (late replies of an abandoned attempt are dropped)
    Integer priority) {}          // 2.33; record patterns must list all 8 components
```

Ways to write one: an `EmbeddedTaskExecutor` bean (below); typed `TaskHandler`s (generated from a
task contract, or a hand-made `TaskRegistration<In, Out>` bean matched by task id or, for an ACTION with
no `task`, its step id) run by `worker-embedded` or `worker-kafka`; a Kafka consumer replying through
`WorkerReply`; or any language over HTTP (`/workflow/api/workers`, Python SDK in `sdks/python`, §7b).

### Embedded worker (mode: embedded)

Register ONE `EmbeddedTaskExecutor` bean; branch on `stepId` (the `topic` is ignored in embedded mode). A hand-written executor replaces `worker-embedded`'s, so it would also receive the built-in `HTTP_CALL`/`LLM_CALL`/`AGENT_CALL` tasks — when you use those embedded, write your workers as `TaskHandler`s instead.

Mind the two `Variable` records: `UpdateStepExecutionCommand` takes `io.mateu.workflow.domain.aggregates.Variable`, while the events (`TaskExecutionRequested`, `TaskStatusChanged`, `ProcessCreationRequested`) use `io.mateu.workflow.dtos.Variable` — import the right one per use.

The bean is called **on the dispatching thread**, which under `workflow.persistence=jpa` is the single outbox relay — the only thread advancing every process in the JVM. A worker that blocks there stops all of them, and the symptom is that processes created afterwards show every step in `CREATED` ("waiting for its preconditions"). Give outbound calls timeouts (a `RestClient` from `builder.baseUrl(url).build()` has none), and set `workflow.embedded.worker-threads` above zero to dispatch through a pool — after giving ACTION steps a `timeout`, which is what recovers a task lost to a crash once delivery no longer means completion. An exception escaping the bean fails the step; reporting `ERROR` yourself is still better, since a throw carries no output variables.

```java
@Bean
EmbeddedTaskExecutor taskExecutor(UpdateStepExecutionUseCase update) {
    return request -> {
        switch (request.stepId()) {
            case "greet" -> {
                String name = varOr(request, "name", "World");
                update.handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(),
                    List.of(new Variable("greeting", "Hello, " + name)),
                    "",                                   // optional log
                    StepExecutionStatus.COMPLETED));
            }
            default -> update.handle(new UpdateStepExecutionCommand(
                request.taskExecutionId(), List.of(),
                "Unknown step: " + request.stepId(),
                StepExecutionStatus.ERROR));
        }
    };
}
```


### Kafka worker (Spring Cloud Stream)

Consume `TaskExecutionRequested` from `downstream` (or the step's `topic`); reply with a `TaskStatusChanged`
on `upstream` through `io.mateu.workflow.worker.WorkerReply`, which retries a refused send and then throws
(so the task is redelivered) instead of silently dropping the reply as a bare `bridge.send` would:

```java
@Bean
Consumer<TaskExecutionRequested> myWorkerTopic(StreamBridge bridge) {
    return req -> {
        try {
            String result = doWork(req.variables());
            WorkerReply.completed(bridge, req, List.of(new Variable("result", result)));
        } catch (Exception e) {
            WorkerReply.failed(bridge, req, List.of(), e.toString());   // the reason reaches the process log
        }
    };
}
```
Handlers must be idempotent (at-least-once).

There is no log component on `TaskStatusChanged`; task logs are emitted through the separate `TaskLogEmitted(String taskExecutionId, MessageType messageType, String message)` event.

### Progress & async

- Report `RUNNING` for long tasks — resets the timeout clock.
- For async work, return immediately and call `UpdateStepExecutionUseCase` later (it's a Spring bean available anywhere).

### Output variables

Worker outputs are **merged into process variables** (overwriting same-named ones), and become available to all later steps and JEXL expressions (forEach iterations excepted: see `output`).

### 7b. Workers over HTTP, any language

`workflow.workers.http.enabled=true`, `workflow.workers.http.topics=payments` (tasks of those topics are
queued — `http_task` with jpa — and leased), `workflow.workers.http.api-keys.<name>=<key>`. Endpoints under
`/workflow/api/workers` (header `X-Api-Key`): `POST /activate {workerId, topics, maxTasks, leaseMs, waitMs}`
(long poll), `POST /tasks/{id}/complete {attempt, variables}`, `/fail {attempt, code, message, retryable}`,
`/heartbeat {leaseToken, extendMs}` → `{cancelled, deadline}`, `/inject`. An expired lease redelivers; a reply
for an old attempt is refused (409). Python SDK (`sdks/python/eventconductor-worker`, stdlib only, not on PyPI):
```python
from eventconductor_worker import Worker, TaskError
worker = Worker("http://localhost:8080", api_key="my-worker-key", concurrency=4)

@worker.task("payments")
def charge(task):
    if task.variables["amount"] == "0":
        raise TaskError("ZERO_AMOUNT", "nothing to charge", retryable=False)
    return {"chargeId": f"ch-{task.process_id}"}

worker.run()
```

### The test worker (`worker-standalone-app`, module `modules/test-worker`)

A worker that does no work: it plays back the scenario the process asks for, so a workflow can be driven through any outcome without writing a worker for it. Start the process with a `TEST_CONFIG` variable holding this JSON:

```json
{
  "default": { "durationMs": 200, "outcome": "COMPLETED" },
  "tasks": {
    "reserve-seat": { "durationMs": 500, "logs": [{ "type": "Info", "message": "checking inventory" }],
                      "variables": [{ "name": "seatId", "value": "12A" }] },
    "charge-card":  { "outcome": "ERROR", "reason": "card declined" },
    "notify":       { "outcome": "NO_REPLY" }
  }
}
```

**The keys are step ids.** They are matched against `taskId` first and `stepId` second. The engine sends an ACTION's task-contract reference (`<id>@<version>`) as the `taskId` when the step declares a `task`, and an empty `taskId` otherwise; it fills the field with `complete-form` for `USER_TASK` and `evaluate-rule` for `RULE`. Anything unstated is inherited from `default`, then from the built-in "take `worker.task-duration` and complete". Per-task fields: `durationMs`, `outcome` (`COMPLETED` | `ERROR` | `NO_REPLY`), `reason`, `logs[{type, message, atMs}]`, `variables[{name, value}]`, `failuresBeforeSuccess` (attempts of one task execution — the engine retries by re-dispatching the same `taskExecutionId`, so this and the step's `attempt_count` agree; pair it with `retries` on the step), `replyTimes`, `ignoreCancellation`.

- `NO_REPLY` reports `RUNNING` and then goes quiet: the scenario for a step timeout.
- Unknown properties and malformed JSON **fail the task** with the parse error as its reason, rather than falling back to a default.
- Overrides saved in the worker's UI (`/_worker`) answer only processes that carry no `TEST_CONFIG`; `TEST_CONFIG` always wins.
- `worker.persistence=jpa` keeps received tasks and overrides in PostgreSQL; `SPRING_PROFILES_ACTIVE=memory` runs it with no database.
- **Against a running deployment** the scenario travels the same way — start the process from the orchestrator UI with a `TEST_CONFIG` variable, or put it on the `ProcessCreationRequested` event: `"variables": [{"name": "TEST_CONFIG", "value": "{\"default\":{\"durationMs\":200}}"}]`. The value is a **string**, so the JSON is escaped inside it. An event whose own JSON is broken never becomes an event — no process is created, nothing is parked in the dead-letter store (conversion fails before any handler runs), and the producer still exits 0; check that processes were created, not that the producer succeeded. A broken `TEST_CONFIG` *string* inside a valid event fails loudly instead: the process runs and its first task reports the parse error.
- **Topic collision:** the test worker and the forms engine both bind `downstream` by default, in different consumer groups, so both receive every task and the worker answers `USER_TASK`s meant for people. Give human tasks their own topic (`"topic": "forms"`) and bind the forms engine there. One shared consumer group does not fix it — they compete for the message.

### Testing definitions in JUnit: the `workflow-test` kit

`io.mateu.workflow:workflow-test` (test scope; package `io.mateu.workflow.test`) runs the user's own definitions in a JUnit 5 test — the real engine in embedded + memory mode, no server/broker/DB — with a clock the test moves. Definitions load from `classpath:/workflows/`; more via `@EventConductorTest(definitions = {"src/test/flows"})` or `runtime.deploy(...)`.

```java
@EventConductorTest
class BookingSagaTest {
    @Test
    void refundsWhenTheCaptureIsDeclined(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete();
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_1"));
        runtime.whenTask("capture-payment").thenFail("CARD_EXPIRED", false);  // final failure
        runtime.whenTask("refund").thenComplete();
        runtime.whenTask("release-room").thenComplete();

        var booking = runtime.start("booking-saga", "B-1", Map.of("amount", 120));
        runtime.advanceTime(Duration.ofDays(3));                              // the P3D TIMER fires

        assertThat(booking).isCompensated().hasPassedSteps("refund", "release").hasNotPassedSteps("capture");
    }
}
```

- **Mocks**: `whenStep(stepId)` wins; `whenTask(x)` matches the task id without version, the step id when the step names no task, or the step's `topic`. Answers `thenComplete(map)`, `thenFail(code)` (retryable) / `thenFail(code, false)` (final), `thenTimeout()` (never answers), `thenAnswer(invocation -> TaskOutcome.complete(...) | fail(...) | noReply())`; consumed in order, the last repeats. An un-mocked task runs the app's real `TaskHandler` when the test boots it (`classes = {WorkflowTestApplication.class, MyHandlers.class}`, needs `worker-embedded`), otherwise waits: answer with `completeTask(p, step, vars)`, `failTask(p, step, code, retryable)`, `completeUserTask(p, step, submitted)` (USER_TASKs always wait unless mocked).
- **HTTP_CALL / LLM_CALL** never reach the network: `whenHttpCall(step).thenRespond(status, body[, headers])` / `.thenIoError()` apply the step's `successStatus`, `retryOn`, `output`; `whenLlmCall(step).thenAnswer(text)` / `.thenFail(code, retryable)` apply `outputVariable`, `usageVariable`, `output` (schema not validated). Unstubbed → the step fails `HTTP_CALL_NOT_STUBBED` / `LLM_CALL_NOT_STUBBED`. Stubs record `requests()`.
- **Time**: `advanceTime(Duration)` / `advanceTimeTo(LocalDateTime)` jump from one due moment to the next (TIMER at its moment, a timeout 1 ms after its limit — it expires strictly after —, retry backoffs with the real policy, `repeat`/`loopBack` `delay`s, cron occurrences — one process each), running every scheduler pass at each; `runUntilIdle()`; `now()`. Every call is synchronous.
- **Other drivers**: `sendMessage(name, correlationKey, vars)`, `publishEvent(destination, type, data)` (triggers + `onEvent`), `publishedEvents(p)`, `cancel(p)`, `process(businessKey)`, `processes(definitionId)`, `invocationsOf(step)` (each with `variables`, `attempt`, `at` = engine time).
- **Assertions** (`WorkflowAssertions.assertThat(process)`): `isCompleted`, `isCompensated`, `isCompensationFailed`, `isFailed`, `isCancelled`, `isRunning`, `hasStatus`, `hasPassedSteps`, `hasNotPassedSteps`, `isWaitingAt(step)`, `hasStepStatus(step, status)`, `stepExecutions(step)` (AssertJ list; a retried step is ONE execution), `hasVariable(name, value)` (text as text, else JSON), `hasVariables`, `doesNotHaveVariable`, `hasReplied()`, `hasReplied(json)`, `hasReplyOutcome`, `hasPublishedEvent(type)`.
- The Spring context is cached across test classes: mocks and invocations reset per test, the clock only moves forward, processes from earlier tests never consume the current test's mocks. Business keys are unique per engine — pass a distinct one or `null` (generated).
- Engine side: business time is read from `io.mateu.workflow.time.EngineClock` (bean `eventconductorClock`, default `Clock.systemDefaultZone()`); every scheduler implements `SchedulerPass.runOnce()`; `workflow.schedulers.background=false` keeps their loops from starting.
- Versus the test worker: the kit tests definitions in the build, in memory, in skipped time; the test worker exercises a running deployment in real time via `TEST_CONFIG`.

---

## 8. Retries, timeouts & sagas (compensation)

- `retries: N` — retry a step up to N times on `ERROR` or `TIMEOUT`.
- `timeout` — on expiry the step goes `TIMEOUT`, then retries if attempts remain, else `ERROR`. A process in `ERROR` (after retries exhausted) can be retried, transitioning back to `RUNNING`.
- **Saga**: mark steps `compensable: true` with a `compensationStepId`. When any step fails after exhausting retries, the engine runs the compensations of **every completed compensable step** (never the step that failed — its work did not happen) **sequentially, in reverse completion order** — latest undone first, each starting only once the previous compensation completes; a step that ran several times (forEach iterations, loop passes) is compensated once per completed run, newest first. When the whole chain finishes the process ends in the terminal **`COMPENSATED`** state; if a compensation itself fails after its own retries, the chain halts and the process ends **`COMPENSATION_FAILED`** (partially rolled back — alert on `eventconductor.compensations.failed`). If nothing compensable had completed, the process stays `ERROR`. Define compensation steps as ordinary `ACTION` steps with **no preconditions** — being named as a `compensationStepId` is what makes them reachable, and the dataflow never starts a step that has nothing to wait for. (Older definitions anchor them with `"preconditionExpression": "false"`; still supported. An anchor without the guard is a live branch of the happy path.)

```json
{
  "id": "booking-saga", "name": "Booking Saga", "version": 1,
  "steps": [
    { "id": "start", "type": "START", "name": "Start" },
    { "id": "reserve-hotel",  "type": "ACTION", "name": "Reserve the hotel", "topic": "hotel-service",
      "preconditionStepId": "start",
      "compensable": true, "compensationStepId": "cancel-hotel", "retries": 2 },
    { "id": "reserve-flight", "type": "ACTION", "name": "Reserve the flight", "topic": "flight-service",
      "preconditionStepId": "reserve-hotel",
      "compensable": true, "compensationStepId": "cancel-flight" },
    { "id": "cancel-hotel",  "type": "ACTION", "name": "Cancel the hotel", "topic": "hotel-service" },
    { "id": "cancel-flight", "type": "ACTION", "name": "Cancel the flight", "topic": "flight-service" },
    { "id": "end", "type": "END", "name": "End", "preconditionStepId": "reserve-flight" }
  ]
}
```

---

## 9. Statuses

### Process
`PENDING` (created) → `RUNNING` → `COMPLETED` | `ERROR`; `RUNNING`/`PENDING` → `CANCELLED`. `ERROR` and `CANCELLED` can be retried / restarted. A failed process that fully rolls back via saga compensation ends in the terminal `COMPENSATED` state; a rollback that halts on a failed compensation ends `COMPENSATION_FAILED` (see §8). A `CONTINUE_AS_NEW` run ends `COMPLETED` with outcome `continued`. `PENDING`/`RUNNING` → `PAUSED` → `RUNNING` on resume (`PAUSED` → `CANCELLED` also works); see §6 for pause semantics.

### Step execution
| Status | Meaning |
|---|---|
| `CREATED` | Scheduled, waiting for the next orchestration tick |
| `PENDING` | Dispatched to worker, awaiting ack |
| `RUNNING` | Worker reported it started |
| `COMPLETED` | Worker reported success |
| `ERROR` | Worker reported failure, or timeout with no retries left |
| `TIMEOUT` | Exceeded `timeout` — retries if attempts remain |
| `AWAITING_RETRY` | Failed with retries left, waiting out its backoff (`workflow.retry.backoff-*`) before re-dispatch |
| `WAITING_ON_LOCK` | A `LOCK` step parked behind another holder, admitted FIFO on release — active work (the process does not complete or error around it), no worker involved |
| `CANCELLED` | Process cancellation, saga compensation, or step never run when `END` fired |

A step runs more than once only through `forEach` (child executions with `parentExecutionId`/`itemIndex`) or `repeat`/`loopBack` (a new execution per pass, `iteration` 1, 2…, the replaced one `superseded`); the process status and percentage read the current execution of each step.

There is **no `SKIPPED` status**. A step whose `preconditionExpression` (a `DISCARD` guard) is falsy is simply never run: it stays `CREATED` and is flipped to `CANCELLED` when the process completes. Because dependent steps require their `preconditionStepId` step to be `COMPLETED`, a never-run step permanently blocks its dependents — give conditional chains an alternative path to `END`.

Workers may only report `RUNNING`, `COMPLETED`, `ERROR`.

### Form execution
`PENDING` → `ASSIGNED` → `COMPLETED`; `CANCELLED`.

---

## 10. Kafka topics (mode: kafka)

| Topic | Direction | Payloads |
|---|---|---|
| `upstream` | into the orchestrator | `ProcessCreationRequested`, `TaskStatusChanged`, `MessageReceived` (`{"type":"message-received","messageName":"...","correlationKey":"...","variables":[{"name":"...","value":"..."}]}`) |
| `downstream` | orchestrator → workers | `TaskExecutionRequested` (per ACTION step `topic`) |
| `outbox` | internal | orchestrator outbox relay |
| `http-calls` / `llm-calls` | orchestrator → built-in workers | `HTTP_CALL` / `LLM_CALL` + `AGENT_CALL` tasks |
| `dead-letter` | out | records parked as permanently unprocessable |
| `human-tasks` | forms engine → out | task lifecycle CloudEvents `io.eventconductor.task.*` |
| destinations | in / out | `workflow.events.destinations.<name>.topic` for PUBLISH_EVENT, triggers, `onEvent` (consumer in `inbound-events-kafka`) |

**Redelivery (main after 2.33.0, #565):** every consumer commits a record only once processed or parked on
`dead-letter`. A retryable failure (optimistic-lock conflict, deadlock, database/broker away) is redelivered
with exponential back-off, without limit, from the failed record — `workflow.consumer.retry.{initial-interval
100ms, multiplier 2.0, max-interval 10s, max-attempts 0 = unlimited (>0 parks on dead-letter), warn-after 10}`;
task consumers (workers, rule runtime, forms engine) `eventconductor.worker.consumer.retry.*`; projector
`projector.consumer.retry.*`; inbound events `workflow.events.consumer.retry-backoff`. Metrics
`eventconductor.events.redelivered{topic,cause}`, `eventconductor.worker.tasks.redelivered`. Up to 2.32.0 the
default batch handler discarded a batch after nine instant retries (fan-out processes could stick).
Kafka clients of `inbound-events-kafka` and the webhook gateway inherit `spring.kafka.security.*`,
`spring.kafka.ssl.*`, `spring.kafka.properties.*` and the binder configuration; per-client overrides
`workflow.events.consumer.properties.*`, `workflow.webhooks.kafka.properties.*`.

Spring Cloud Stream bindings. The engine contributes its own as lowest-precedence defaults —
destinations, a consumer group per binding, and `consumer.batch-mode=true`, which its batch
consumers require (without it the payload arrives as a `byte[]` and every event dies with
`ClassCastException: class [B cannot be cast to class java.util.List`). The function list is
yours, since only the application knows what it composes:
```properties
spring.cloud.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

---

## 11. Java API summary

### Entry points
- `ProcessUpstreamEventUseCase.handle(ProcessUpstreamEventCommand)` — wraps `ProcessCreationRequested` and other upstream events.
- `CancelProcessUseCase.handle(CancelProcessCommand)` / `RetryProcessUseCase.handle(RetryProcessCommand)` / `RestartProcessUseCase.handle(RestartProcessCommand)` — cancel / retry / restart a process by id.
- `SetProcessVariablesUseCase`, `CompleteStepManuallyUseCase`, `RetryFromStepUseCase`, `MigrateProcessUseCase` (`application.usecases.process.operate`) — audited operations, each command with a `reason`.
- `ProcessOperationsService` — what REST and MCP call (start honouring `startPolicy`/`businessKeyPolicy`, operations).
- `SyncInvocationService` — synchronous invocations in code; `InboundEventService.receive(...)` — inject a domain event (embedded).
- `PauseProcessUseCase.handle(PauseProcessCommand)` / `ResumeProcessUseCase.handle(ResumeProcessCommand)` — pause / resume a process by id (see §6).
- `PauseWorkflowUseCase.handle(String)` / `ResumeWorkflowUseCase.handle(String)` — pause / resume a whole definition by id (bulk + born-paused new instances).
- `UpdateStepExecutionUseCase.handle(UpdateStepExecutionCommand)` — report worker progress.

```java
new UpdateStepExecutionCommand(
    taskExecutionId,                 // from TaskExecutionRequested
    List.of(new Variable("k","v")),  // output variables
    "log message",                   // optional
    StepExecutionStatus.COMPLETED);  // RUNNING | COMPLETED | ERROR
```

### Repositories

All extend `CrudStore<T>` (`findById` → `Optional<T>`, `save`, `findAll`, `deleteAllById`, `find`). There is no `findByStatus` anywhere.
- `ProcessRepository`: + `findByBusinessKey(String)` → `Optional<Process>`, `countByStatus(ProcessStatus)`.
- `StepExecutionRepository`: + `findByProcess(Process)`, `findPendingOrRunning()`.
- `WorkflowDefinitionRepository`: bare `CrudStore<WorkflowDefinition>`.

### Analytics
- `ProcessAnalyticsService.analyze(definitionIdOrName, TimeWindow)` / `analyzeAll(TimeWindow)` — per-definition analytics computed on demand in any mode: instance counts by status, completion/error/cancellation rates, throughput per day, avg/p95 process duration, avg/p95 per-step duration with the slowest step flagged as bottleneck. `TimeWindow.lastDays(30)` / `TimeWindow.all()`.

### Observability
Micrometer metrics for the workflow, forms and rule engines — exposed at `GET /actuator/prometheus` when a Prometheus `MeterRegistry` is on the classpath (`micrometer-registry-prometheus` + `management.endpoints.web.exposure.include=health,prometheus`) — plus OTLP tracing configured via `TRACING_SAMPLING` (`management.tracing.sampling.probability`) and `OTLP_TRACING_ENDPOINT` (`management.opentelemetry.tracing.export.otlp.endpoint`). Full reference: `doc/src/content/docs/reference/observability.md`.

### Interfaces & records
```java
@FunctionalInterface interface EmbeddedTaskExecutor { void execute(TaskExecutionRequested request); }
record Variable(String name, String value) {}
```

### Domain model (selected fields)
- `Process`: `id`, `name`, `workflowDefinitionId`, `workflowDefinitionVersion`, `workflowDefinitionJson`, `businessKey`, `variables`, `status`, `completionPercentage`, `created`, `started`, `finished`, `pausedAt` (set while `PAUSED`; used to shift step clocks on resume), `parentStepExecutionId` (set on child processes started by a parent `PROCESS` step; `null` otherwise).
- `StepExecution`: `id`, `processId`, `workflowDefinitionId`, `stepId`, `stepJson`, `variables`, `status`, `workerId`, `startedAt`, `finishedAt`, `attemptCount`. The step-execution `id` **is** the `taskExecutionId` used in events; there is no separate field, no `retryCount`/`completedAt`/`log`.
- `WorkflowDefinition`: `id`, `name`, `version`, `description`, `paused`, `disabled`, `archived` (runtime flags), `limitConcurrentExecutions`, `maxConcurrentExecutions`, `enqueueOnLimit`, `cronExpression`, `defaultMaxStepExecutions`, `steps`.

---

## 12. Git import (any persistence) & working copies (jpa)

**Git import** — clone repos at startup and import every valid definition file (has `name` + `steps`):
```yaml
workflow:
  git-import:
    webhook-secret: mysecret        # verifies inbound webhooks (HMAC or token, per provider); without it they are refused (401) unless Spring Security protects the app or workflow.message-api.allow-anonymous=true
    repositories:
      - url: https://github.com/your-org/workflow-defs.git
        branch: main
        username: my-user           # optional (token auth)
        password: ghp_xxx           # optional
```
Also triggerable via the MCP tool `importWorkflowDefinitionsFromGit`, or a git webhook to `POST /workflow/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`; responds 202, imports in background). The webhook reloads **only the repository and branch named in the push** (payload parsed; unmatched pushes are acknowledged and ignored; an unparseable payload reloads everything). Definitions removed from a repo are **pruned** — workflow definitions are archived, forms/rules deleted (git-imported only; classpath/hand-authored never touched; tracked per instance, resets on restart). Verification per provider using `webhook-secret`: GitHub/Bitbucket HMAC-SHA256 (`X-Hub-Signature-256`/`X-Hub-Signature`), GitLab token (`X-Gitlab-Token`), generic token (`X-Webhook-Token`); with a blank secret an unsigned webhook is refused (401) unless Spring Security protects the app or `workflow.message-api.allow-anonymous=true`. Bodies over `workflow.rest.max-body-bytes` get 413. Forms and rules expose the same at `/forms/webhooks/{provider}` and `/rules/webhooks/{provider}`.


---

## 13. Human tasks (forms-engine)

`USER_TASK` steps pause the process and create a `FormExecution` for a form identified by `formId`. Users submit via the UI or MCP tools; on submission the form's field values are merged into the process variables and the step completes. See `doc/src/content/docs/guides/form-definitions.md` and `user-tasks.md`.

---

## 14. AI / MCP integration

The engine exposes MCP tools (Spring AI MCP server; Mateu `/mateu/mcp`) so AI agents can operate it. Like the
admin UI, MCP needs Spring Security or `workflow.message-api.allow-anonymous=true` (403 otherwise);
`spring.ai.mcp.server.enabled=false` switches it off.

- Processes: `listProcesses` (with `query`), `getProcessDetails`, `getAgentTranscript`, `findProcessByBusinessKey`, `listRunsOfBusinessKey`, `listInFlightProcesses`, `countProcessesByStatus`, `getProcessLogs`, `retryProcess`, `restartProcess`, `pauseProcess`, `resumeProcess`, `pauseWorkflow`, `resumeWorkflow`, `sendMessage`, `getWorkflowAnalytics`, `findBottleneck`, `importWorkflowDefinitionsFromGit`.
- Operations: `setProcessVariables`, `completeStepManually`, `retryProcessFromStep`, `dryRunProcessMigration`, `migrateProcess`, `listIncidents`, `getIncidentProcesses`, `retryIncident`, `revealProcessVariable`.
- Batch: `createBatchOperation`, `migrateProcessesInBatch`, `listBatchOperations`, `getBatchOperation`, `cancelBatchOperation` (no `cancelProcess` tool — a CANCEL batch does it).
- Updates: `updateProcess`, `updateWithStart`, `listProcessUpdates`.
- Schedules: `listSchedules`, `getSchedule`, `createSchedule`, `updateSchedule`, `deleteSchedule`, `pauseSchedule`, `resumeSchedule`, `runScheduleNow`, `exportScheduleYaml`, `listWorkflowDefinitions`.
- Events: `listEventTriggers`, `pauseEventTrigger`, `resumeEventTrigger`, `createEventTrigger`, `updateEventTrigger`, `deleteEventTrigger`, `exportEventTriggerYaml`, `listReceivedEvents`, `publishInboundEvent`.
- Forms: `searchTasks`, `listForms`, `listFormExecutions`, `getFormExecution`, `importFormsFromGit`. Rules: `listRules`, `getRule`, `saveRule`, `validateRule`, `deleteRule`, `evaluateRule`, `importRulesFromGit`.

There is no start-process tool: start over REST, with a trigger (`publishInboundEvent`) or `updateWithStart`. See `doc/src/content/docs/guides/mcp-overview.md`.

---

## 14b. Observability

- **Metrics** — Micrometer; Prometheus scraping by default, and OTLP push when
  `management.otlp.metrics.export.enabled=true`.
- **Tracing** — OpenTelemetry over OTLP, off until `management.tracing.sampling.probability > 0`.
  The outbox row carries the producing trace's W3C `traceparent`, and the relay publishes as a
  continuation of it, so one process is **one trace** rather than one per hop. The engine names
  `eventconductor.step-over`, `eventconductor.dispatch-step` and `eventconductor.correlate-message`.
- Both go through ports with no-op defaults (`WorkflowMetrics`, `WorkflowTracing`), so the engine
  libraries run with no observability dependencies at all.

## 15. Minimal fully-embedded example (needs `spring-boot-starter-webmvc` only to serve HTTP)

`src/main/resources/application.properties`:
```properties
workflow.mode=embedded
workflow.persistence=memory
```
`src/main/resources/workflows/hello.json`:
```json
{ "id": "hello", "name": "Hello", "version": 1,
  "steps": [
    { "id": "start", "type": "START",  "name": "Start" },
    { "id": "greet", "type": "ACTION", "name": "Greet", "preconditionStepId": "start" },
    { "id": "end",   "type": "END",    "name": "Done", "preconditionStepId": "greet" } ] }
```
Application:
```java
@WorkflowEmbeddedApplication
public class App {
    public static void main(String[] a) { SpringApplication.run(App.class, a); }

    @Bean
    EmbeddedTaskExecutor exec(UpdateStepExecutionUseCase update) {
        return req -> update.handle(new UpdateStepExecutionCommand(
            req.taskExecutionId(), List.of(new Variable("greeting", "Hello!")),
            "", StepExecutionStatus.COMPLETED));
    }
}
```

---

## 16. Validating and testing definitions

**Maven plugin** — `io.mateu.workflow:workflow-maven-plugin`, goal `validate` (prefix `eventconductor`, bound
to `process-resources`):
```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <version>${eventconductor.version}</version>
  <executions><execution><goals><goal>validate</goal></goals></execution></executions>
</plugin>
```
`mvn eventconductor:validate` checks `src/main/resources/{workflows,forms,rules,tasks}` (plus `cron.yaml`,
`events.yaml`) against the engine's schemas (unknown keys are errors) and its semantic rules (roots, dangling
ids, cycles, JEXL, loops, HTTP/LLM/agent blocks, triggers, schedules, retention, sensitive-variable rules,
task contracts); warnings for JOINs on guarded steps and CHOICEs without a default. Only at engine load:
TIMER `duration: 0`, blank message fields, missing encryption keys. Schema messages are in the JVM's language
(`MAVEN_OPTS=-Duser.language=en`). Other goals: `check-migration` (§6b), `generate-worker-sources` (§4 ACTION).

Frequent errors: a step without `name`; `A workflow can have at most one START step`; `Step 'X' has no
preconditions and is not a START, a WAIT_FOR_MESSAGE, another step's compensation or an agent's tool — nothing
would ever start it.`; `Step 'X' references unknown precondition step 'Y'.`; `Timer step 'X' must define a
duration, an untilVariable or an until.`; `Message step 'X' must define a correlationExpression.`;
`HTTP_CALL step 'X' must define exactly one of connection and url.`; `Step 'X' loopBack must define
maxIterations…`; `AGENT_CALL step 'X' maxSteps is required: an agent must be bounded.`

**Test kit** — see §7 *Testing definitions in JUnit*. A complete saga test:
```java
@EventConductorTest
class BookingSagaTest {
    @Test
    void capturesAfterTheCoolingOffPeriod(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete(Map.of("roomId", "R-12"));
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_1"));
        runtime.whenTask("capture-payment").thenComplete();
        var booking = runtime.start("booking-saga", "B-1", Map.of("amount", 120));
        assertThat(booking).isWaitingAt("cooling-off");
        runtime.advanceTime(Duration.ofDays(3));
        assertThat(booking).isCompleted().hasPassedSteps("reserve", "charge", "cooling-off", "capture");
    }
}
```
(`import static io.mateu.workflow.test.WorkflowAssertions.assertThat;`). Also `whenAgent(step)`, `update(...)`,
`updateWithStart(...)`, `hasAppliedUpdates(...)`; `advanceTime` also stops at escalations, flow-control windows
and rate-limit permits.

## 17. Security, credentials and sensitive data

- **Access** — see §2 *Secure by default*.
- **HTTP_CALL credentials** — named profiles `workflow.http.auth.<name>` (`basic`, `bearer`, `api-key`,
  `oauth2-client-credentials`) or inline `auth` with `${secret:NAME}` only, resolved from
  `workflow.http.secrets.<NAME>` (since 2.27; `workflow.http.secrets-from-environment=true` restores env lookup).
  Since 2.28 credentials are **bound to hosts**: a profile applies through a `connection`, or on an absolute
  `url` only when the host is in `workflow.http.auth.<name>.hosts` (or `workflow.http.allowed-hosts` is set);
  inline `${secret:…}` on an absolute URL needs `allowed-hosts`; else the step fails `HTTP_FORBIDDEN_AUTH`
  (non-retryable). Internal addresses are always refused for absolute URLs (`HTTP_FORBIDDEN_HOST`).
  ```yaml
  workflow:
    http:
      connections:
        payments: { base-url: "https://payments.example.com", auth: payments-key }
      auth:
        payments-key: { type: api-key, header: X-Api-Key, value: "${PAYMENTS_API_KEY}" }
        partner: { type: bearer, token: "${PARTNER_TOKEN}", hosts: [ "api.partner.com" ] }
      secrets:
        PARTNER_TOKEN: "${PARTNER_TOKEN}"
  ```
- **LLM keys** only in `workflow.llm.connections.<name>.api-key`.
- **Sensitive variables** (2.32) — `sensitiveVariables: [cardNumber, "patient.*"]` (and a PROCESS step's
  `sensitiveOutputs: true`) require `workflow.encryption.keys.<id>` (base64 of 32 bytes, `openssl rand -base64 32`)
  and `workflow.encryption.active-key` (with several keys); otherwise the definition is refused. Values are
  sealed with AES-256-GCM (`ec:enc:v1:<keyId>:…`) in the database, outbox, Kafka and HTTP task queue; masked
  `••••` in console/REST/MCP; revealed by `POST /workflow/api/processes/{id}/variables/{name}/reveal {reason}` /
  MCP `revealProcessVariable` for `workflow.security.sensitive.reveal-roles` (audited). Embedded workers see
  plaintext; Kafka/HTTP workers see ciphertext unless configured with the same keys. Refused at load when
  computed from a sensitive variable: search attributes, correlation expressions, lock keys, `startPolicy.key`,
  trigger business keys. `LLM_CALL`/`AGENT_CALL`/update replies reading one need `allowSensitiveVariables: true`.
- **Webhooks** — git-import webhooks need `workflow.git-import.webhook-secret` (2.28); the `webhook-gateway` verifies
  GitHub / HMAC signatures (`workflow.webhooks.endpoints.<name>.{provider, destination, secret}`); its `rest`
  transport needs `workflow.webhooks.engine.api-key`.

## 18. Upgrade notes 2.27 → 2.33 (breaking changes and rolling order)

- **2.27** — `${secret:}` only from `workflow.http.secrets`; `/workflow/api/**` refuses anonymous (401). With
  `workflow.analytics.rollup=true`, stop all 2.26 pods before the first 2.27 pod (V43).
- **2.28** — consoles/MCP refuse anonymous (403), `workflow.ui.enabled`; `@EnableWorkflowUi` for embedded apps
  mounting the UI; HTTP credentials bound to hosts; unsigned git webhooks refused; standalone apps need
  `SECURITY_PASSWORD`; rules gRPC off; `trust-forwarded-token`; V45 backfill (quiet window);
  `TaskExecutionRequested.attempt` / `TaskStatusChanged.attempt` appended (record patterns).
- **2.29** — loops; `maxSuccessfulExecutions` / `defaultMaxStepExecutions` enforced; V47.
- **2.30** — search attributes, batch operations, HTTP workers, incidents; V48–V50.
- **2.31** — migration, human-task assignment/escalation; forms V8; claimed tasks are `ASSIGNED`. **Upgrade forms
  engines and workers before any definition uses `escalation`.**
- **2.32** — `AGENT_CALL`; sensitive variables need encryption keys; V53.
- **2.33** — updates, flow control, priority, `CONTINUE_AS_NEW`, `businessKeyPolicy`; V55, V56 (brief lock per
  ALTER), V56.1 (concurrent indexes); `TaskExecutionRequested.priority` appended. **Don't import definitions using
  `CONTINUE_AS_NEW` or a reusing `businessKeyPolicy` until every pod runs 2.33.**
- Own kafka-mode apps with triggers/`onEvent` need `inbound-events-kafka` (since 2.26).

## Canonical sources of truth (this repo)

- `doc/public/eventconductor-ai-compact.md` — the compact version of this file.
- `doc/src/content/docs/guides/` — workflow-definitions, starting-a-process, workers, http-workers, retries-timeouts-compensation, testing-workflows, request-response, loops-and-multi-instance, operating-running-processes, searching-and-bulk-operations, migrating-running-processes, updating-running-processes, flow-control, long-running-processes, sensitive-data-and-encryption, human-tasks-assignment-and-escalation, agents-with-tools, http-calls, llm-calls, domain-event-triggers, webhooks, mcp-*.
- `.claude/skills/eventconductor*/` — the Claude Code skills (progressive: SKILL.md → reference/*.md), whose examples are validated by the build.
- `doc/src/content/docs/reference/` — step-types, statuses, configuration, kafka-topics, java-api, processes-rest-api, http-worker-api, maven-plugin.
- `modules/workflow-engine/src/main/resources/workflow-definition-schema.json` — the JSON Schema for definitions.

Prefer these if anything here looks out of date.
