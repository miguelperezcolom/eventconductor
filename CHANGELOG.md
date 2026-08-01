# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Whole-process saga rollback in reverse execution order.** When any step fails or times out
  after exhausting its retries, the engine now compensates **every executed rollbackable step**
  (completed steps plus the one that just failed) — not only the failed step's own
  compensation — running them **sequentially, in reverse execution order**: the latest-executed
  step is undone first, and each compensation starts only once the previous one completes. The
  next compensation is derived purely from persisted step-execution state (new
  `CompensationService`), so it is idempotent under redelivery and across restarts. A single
  failed rollbackable step is just the degenerate case of this cascade. See the new
  `COMPENSATED` terminal state under Changed.
- **Git reload webhook: multiple providers, targeted reload and pruning.** The
  `POST /{engine}/webhooks/{provider}` endpoint (workflow, forms and rule engines) now accepts
  `github`, `gitlab`, `bitbucket` and `generic` (`/github` keeps its behaviour), each
  authenticated with the configured `webhook-secret` — GitHub/Bitbucket HMAC-SHA256
  (`X-Hub-Signature-256`/`X-Hub-Signature`), GitLab (`X-Gitlab-Token`) and generic
  (`X-Webhook-Token`) tokens; a blank secret skips verification. The push payload is parsed to
  reload **only the repository and branch that changed** (an unmatched push is acknowledged and
  ignored; an unparseable payload falls back to reloading everything). Shared, reusable helpers
  live in a new `io.mateu.workflow.webhook` package. See pruning under Changed.
- **Workflow graph editor: multiple incoming preconditions per step.** The graph now renders
  and edits several `preconditionStepIds` into a single step (one edge per precondition, a
  multi-select editor) — the engine already honoured them; only the editor had modelled a
  single incoming edge.
- **Workflow graph editor: restyled, more expressive SVG.** The graph is redrawn in a richer
  visual language on the same Lit + ELK stack: per-type node cards with a corner glyph and an
  uppercase caption (`→ topic`, `👤 form`, `ƒ rule`, `⨝ JOIN`, `✉→ message`, …), dashed
  `FORK`/`JOIN` gateways, orthogonal rounded edges with arrowheads, a themeable palette
  (`--ec-*` custom properties, with a Lumo dark-mode mapping), and a step's
  `preconditionExpression` guard shown as a chip on its incoming edge.

### Changed
- **BREAKING: a fully compensated process ends `COMPENSATED`, not `ERROR`.** A failed process
  that runs its saga rollback to completion now reaches the new terminal
  `ProcessStatus.COMPENSATED` instead of remaining `ERROR`; if a compensation itself fails
  after its retries, the chain halts and the process stays `ERROR`. `COMPENSATED` is a sticky
  terminal failure state (like `ERROR`), distinguished by whether the side effects were undone.
  Consumers, queries and dashboards that treat `ERROR` as the only failure terminal — and a
  parent `PROCESS` step, which now also errors on a `COMPENSATED` child — should account for
  the new state.
- **BREAKING: the git reload webhook reloads a subset and prunes removed definitions.**
  Previously every webhook call re-imported **all** configured repositories and only
  added/updated definitions. It now reloads only the repository and branch named in the push,
  and definitions that were removed from a repo are **pruned** — workflow definitions are
  archived (`ARCHIVED`), forms and rules are deleted (git-imported definitions only, never
  classpath or hand-authored ones; tracked per running instance). Only definitions with an
  explicit `id` are prune-tracked.

## [1.0-beta.013] - 2026-07-31

Cumulative since `1.0-beta.010` — releases `1.0-beta.011` and `1.0-beta.012` were cut
without changelog sections, so their contents are included below.

### Added
- **Pause/play for processes and workflow definitions.** New `ProcessStatus.PAUSED`:
  `PauseProcessUseCase` pauses a `PENDING`/`RUNNING` process and `ResumeProcessUseCase`
  puts it back to `RUNNING`. Pause freezes the frontier, not in-flight work — running
  workers finish and their reports are accepted (steps complete, variables merge), and
  messages still complete `WAIT_FOR_MESSAGE` steps — but successors do not start until
  resume. Clocks freeze too: timeout and TIMER scanning skip paused processes and, on
  resume, every non-terminal started step's `startedAt` is shifted forward by the pause
  duration (recorded in the new `Process.pausedAt`), so step timeouts and timer
  due-moments resume where they left off; blocking-error handling is deferred the same
  way, and cancelling from `PAUSED` works. At the definition level, a new runtime
  `paused` flag (orthogonal to the `DRAFT`/`ACTIVE`/... lifecycle; in the schema as
  nullable boolean, default `false`, so exports round-trip): `PauseWorkflowUseCase` sets
  it and pauses all the definition's `PENDING`/`RUNNING` processes, while new instances —
  cron occurrences included — are still accepted and created **born-`PAUSED`**;
  `ResumeWorkflowUseCase` clears it and resumes all its `PAUSED` processes. Surface:
  **Pause**/**Resume** toolbar actions on the process detail and on the definition detail
  (plus a **Paused** row), and four MCP tools — `pauseProcess`, `resumeProcess`,
  `pauseWorkflow`, `resumeWorkflow`. Flyway migration V6 in the orchestrator app
  (`process_entity.paused_at`, `workflow_definition_entity.paused`).
- **`START` step type — explicit workflow entry points.** A no-worker node that completes
  instantly at process creation, fanning the flow out to its successors. A `START` must have
  no preconditions, and declaring several gives the process concurrent entry branches. Every
  flow must now enter through a `START` or a `WAIT_FOR_MESSAGE` (see the roots rule under
  Changed).
- **`FORK` and `JOIN` are now implemented, on multi-preconditions.** Steps gain
  `preconditionStepIds` (array): ALL the listed steps must complete before the step starts;
  the singular `preconditionStepId` remains valid (the plural wins when both are set). `FORK`
  and `JOIN` are no-worker nodes that complete instantly — `FORK` is the explicit fan-out
  (all its successors start concurrently when it completes), and `JOIN`'s barrier is exactly
  its multiple preconditions (the converge point of parallel branches). Precondition-cycle
  detection is now a DFS over the multi-edge graph. The Maven plugin emits a build-time
  warning (never a failure) when a `JOIN` waits directly on a guarded step — if the guard is
  false the join never fires and the flow beyond it is cancelled.
- **`PROCESS` step type — child workflows are now implemented.** A `PROCESS` step (required
  `childWorkflowDefinitionId`, which must differ from the workflow's own id) starts a child
  process carrying ALL parent variables under the deterministic businessKey
  `parent:<stepExecutionId>` — idempotent, duplicate creation events are deduped. The parent
  step waits `PENDING`; when the child completes, the parent step completes and copies back
  only the child variables named in the new `outputVariables` step field (absent/empty =
  none); a child ending `ERROR` or `CANCELLED` puts the parent step in `ERROR` (normal
  retry/compensation pipeline), and `timeout` bounds the wait. `Process` gains
  `parentStepExecutionId` (Flyway migration V5 in the orchestrator app). Cancellation
  propagates parent→child: a parent `PROCESS` step ending `CANCELLED`, `ERROR` or `TIMEOUT`
  (retries exhausted) cancels a still-running child, cascading to grandchildren; while
  retries remain the child keeps running and a retried step re-attaches to it through the
  deterministic business key.
- **Graph and build-time validation support for the new model.** The workflow graph renders
  multi-precondition edges and `START`/`FORK`/`JOIN` nodes, and the Maven plugin's
  `SpecValidator` mirrors all the new invariants (roots rule, START-without-preconditions,
  plural precondition references, multi-edge DFS cycle detection, the PROCESS child id).
- **`SEND_MESSAGE` step type — fire-and-forget in-engine messaging.** The throw side of
  `WAIT_FOR_MESSAGE`: on start the engine evaluates the step's `correlationExpression` (JEXL,
  same context as preconditions), emits a `MessageReceived(messageName, correlationKey,
  variables)` through the outbox and completes the step immediately — no worker, no `ACTION`
  step needed for process-to-process signaling. The new optional `messageVariables` field
  (array of process-variable names) selects which variables the outgoing message carries;
  empty or absent means none — process state is never sent implicitly. Delivery is not
  acknowledged and a message matching no waiting process is discarded (not buffered). A
  missing `messageName`/`correlationExpression` or an unevaluable correlation key puts the
  step in `ERROR` (fail loud, normal retry/compensation pipeline) — deliberately not the
  silent fail-closed of precondition guards. The Maven plugin's `SpecValidator` now also
  checks `correlationExpression` JEXL syntax at build time.
- **`message-received` deliverable via the Kafka `upstream` topic.** `MessageReceived` is now a
  registered `DomainEvent` subtype (`"type": "message-received"`), so external producers can
  resume waiting `WAIT_FOR_MESSAGE` steps by publishing raw JSON to `upstream` — previously
  only REST (`POST /workflow/api/messages`), the MCP `sendMessage` tool or the Java API could.
- **Engine observability — metrics parity across all engines.** The Micrometer metrics pattern
  that already existed in the workflow engine (an `application/out` port with no-op defaults, a
  Micrometer implementation in `autoconfigure`, and an autoconfiguration guarded on a
  `MeterRegistry` bean) has been mirrored into the other engines. Metrics stay inert unless the
  host application provides a `MeterRegistry` (e.g. via Spring Boot Actuator), so libraries still
  run with zero observability dependencies.
  - **forms-engine** (`FormsMetrics`): `eventconductor.forms.task.created`,
    `eventconductor.forms.task.completed`, `eventconductor.forms.task.cancelled`,
    `eventconductor.forms.task.duration`, `eventconductor.forms.imported`.
  - **rule-engine** (`RuleCatalogMetrics`): `eventconductor.rule.catalog.saved`,
    `eventconductor.rule.catalog.deleted`, `eventconductor.rule.catalog.imported`,
    `eventconductor.rule.catalog.served`.
  - **rule-runtime** (`RuleRuntimeMetrics`): `eventconductor.rule.evaluation.count`,
    `eventconductor.rule.evaluation.duration`, `eventconductor.rule.evaluation.cache`. The
    runtime keeps working as a plain (non-Spring) library — the metrics port defaults to a no-op
    via overloaded constructors.
- **Distributed tracing (OpenTelemetry over OTLP).** The `orchestrator`, `forms` and `rule`
  standalone apps now ship `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` and
  expose `management.tracing` / `management.otlp.tracing` configuration. Tracing is **off by
  default** (`TRACING_SAMPLING=0.0`); set `TRACING_SAMPLING` and `OTLP_TRACING_ENDPOINT` to emit
  spans. Because it is enabled at the app layer, HTTP, Kafka (Spring Cloud Stream) and JDBC calls
  are auto-instrumented and trace context propagates across the engines' async boundaries without
  any engine-code changes — consistent with the metrics' "optional, host-activated" design.
- **Observability docs** — the docs site gains a dedicated Reference → Observability page
  (`doc/src/content/docs/reference/observability.md`) covering the metrics of every engine
  (workflow, forms, rule catalog, rule runtime) plus a Distributed tracing section (how to
  enable Prometheus scraping and OTLP tracing); the Configuration reference keeps a short
  pointer to it.
- **Boot without the database and resume when it appears.** `DbLockDialectFactory` was the only
  startup-time database access left: it now falls back to inferring the lock dialect from the
  JDBC url when the database is unreachable (the pollers already retry every tick), so an
  orchestrator configured for resilience (lazy pool, `ddl-auto: none`, explicit dialect) boots
  in seconds with PostgreSQL down, and a process parked mid-flight with pending outbox rows
  completes once the database returns. Proven by a new distributed chaos test (DIST-08) that
  pauses/resumes PostgreSQL with `docker pause`, like the Kafka chaos tests.
- **REST endpoint to deliver messages to `MESSAGE` steps.** `POST /workflow/api/messages`
  publishes a `MessageReceived` through the same upstream surface as Kafka, the embedded
  publisher and the `sendMessage` MCP tool, so webhooks and SaaS callbacks that cannot produce
  to Kafka can resume waiting `MESSAGE` steps. Fire-and-forget (`202 Accepted`), with an
  optional `X-Api-Key` guard configured via `workflow.message-api.api-key`.
- **Read-only workflow definition detail view with graph.** Selecting a definition in the CRUD
  now opens a dedicated `WorkflowDefinitionDetailView` (the "view" action) instead of the
  editor: name as the title, lifecycle status as a header badge, a compact property list and a
  read-only ELK graph side by side, with the steps as a full-width band below. The editor
  layouts were tidied (5-column definition grid; the Step form's Main / Precondition /
  Execution / Reliability sections sit side by side), a `PROCESS` step can no longer pick the
  workflow it belongs to as its child, and the graph component gains a read-only mode (toolbar
  and node-detail panel hidden, capped height with a full-screen expand button).
- **Lifecycle actions on the detail view + Export YAML.** The read-only detail view now carries
  the full lifecycle toolbar (*Promote to production*, *Create working copy*, *Disable*,
  *Enable*, *Reactivate*, *Archive*) with the same visibility rules as before, so an `ACTIVE`
  definition can again be disabled or copied from the UI. Any `DRAFT` is now promotable (a
  standalone draft is activated in place), and *Export YAML* downloads the definition in the
  exact shape the git/classpath importers read back.
- **Definition-level guardrails against runaway loops.** `WorkflowDefinition.checkInvariants()`
  now rejects precondition cycles (a step waits for its `preconditionStepId` to complete, so a
  cycle deadlocks; the error names the cycle), and the model and JSON schema gain per-step
  execution caps — `Step.maxSuccessfulExecutions` and
  `WorkflowDefinition.defaultMaxStepExecutions` (`0` = inherit/unbounded) — carried through
  working copies, imports and persistence, to be enforced when step re-execution lands.

### Changed
- **BREAKING: the execution model is now pure dataflow.** A step starts when it is `CREATED`,
  ALL its preconditions have `COMPLETED` and its `preconditionExpression` is truthy — and
  every eligible step starts **concurrently**. The old scheduling semantics are gone: array
  order no longer matters, an active step no longer serializes independent chains, and
  `parallel: true` no longer opts into concurrency — the flag is **deprecated and ignored**
  (it still deserializes, so persisted stepJson and old files keep loading, but it has no
  effect). Parallelism is expressed structurally: shared preconditions fan out (`FORK` makes
  it explicit), multiple preconditions form a barrier (`JOIN`).
- **BREAKING: roots rule — every flow must enter through a `START` or `WAIT_FOR_MESSAGE`.**
  A step with no preconditions must be one of those two types; definitions violating this are
  rejected at load (and by the Maven plugin at build time). Migration: add one `START` step
  and point your old first steps at it; anchor compensation steps to the step they compensate
  with `"preconditionExpression": "false"` (the compensation pipeline starts them directly,
  ignoring the guard).
- **BREAKING: `MESSAGE` step type renamed to `WAIT_FOR_MESSAGE`, and `correlationExpression`
  is now required on both message step types.** New or reimported definitions must use the
  new name and declare `correlationExpression` explicitly — the old defaults-to-`businessKey`
  behavior is gone; add `"correlationExpression": "businessKey"` to keep it. A legacy
  deserialization alias keeps old persisted stepJson and definition files loading (`MESSAGE`
  maps to `WAIT_FOR_MESSAGE`, and those legacy steps retain the businessKey fallback), so
  in-flight processes survive the upgrade. To support the idiom, `businessKey` is now seeded
  as a plain variable into every JEXL evaluation context (correlation **and** precondition
  expressions) — property access such as `process.businessKey` was never evaluable, because
  the JEXL engine deliberately runs with `RESTRICTED` permissions on untrusted expressions.
- **Upgraded Mateu to `3.0-alpha.271`.** `3.0-alpha.271` is a breaking release that removed the
  UI CRUD API (`CrudRepository`, `CrudAdapter`, `CrudEditorForm`, `CrudCreationForm`,
  `ListingBackend`, the `core.infra.declarative.Listing` base and `AutoNamedView`) and split
  `Searchable` into a marker interface plus a new `SearchableText`. The admin-UI layer of all
  engines was migrated:
  - `CrudRepository` → `CrudStore` (the method set is unchanged) across ~24 ports/pages/adapters.
  - Pages whose detail view differs from the row (`Processes` → `SimpleProcessViewModel`,
    `WorkflowDefinitions` → `WorkflowDefinitionDetailView`) and the read-only `Tasks` listing now
    extend `Crud<View, …>` directly instead of `AutoCrud`/`FilteredAutoCrud` (which pin
    `View = Row`), because `Navigable.view()` is now generically typed.
  - `rule-engine` was pinned to Mateu `3.0-alpha.222`; it now tracks `${mateu.version}` like the
    rest of the reactor.
  - In the demo services (booking, content, control-plane, shell, users) the `CrudAdapter` layer
    was dissolved into the crud itself: every crud now implements the whole lifecycle
    (`search(SearchRequest)`, `view`, `edit`, `creationForm`, `save`, `create`, `deleteAllById`)
    in a single `Crud`/`AutoCrud`/`FilteredAutoCrud` subclass, and the demo `*CrudAdapter`
    classes were deleted. The workflow engine keeps its adapters (`SimpleProcessCrudAdapter`,
    `StepExecutionsCrudAdapter` and the process child-crud adapters) as collaborators of the
    migrated pages.
  - Testbench UI apps declare `spring-boot-starter-webmvc` explicitly: the Mateu MVC annotation
    processor now generates SSE-capable controllers and the engines only carry the starter as
    `optional`.
  - Everything builds against the released `3.0-alpha.271` — the engine reactor and the
    standalone demo and testbench apps (which have their own poms outside the reactor) alike.
- **The Workflow Definitions admin page is now read-only** (list + rich detail view). Definitions
  are authored as YAML and loaded from the classpath, Git or the database, and were never created
  or edited from this page; the write actions are disabled rather than reimplemented on the new
  API.

### Removed
- Dead `ProcessCrudAdapter` (superseded by `SimpleProcessCrudAdapter`; only referenced by an
  unused import) was deleted as part of the Mateu 271 migration.

### Fixed
- **`END` no longer records co-eligible sibling steps as `COMPLETED` without running them.**
  When an `END` became eligible in the same transition as another executable step, that step
  was silently marked `COMPLETED` even though it never ran; it is now `CANCELLED` like every
  other in-flight step the `END` terminates.
- **`MessageReceived` was missing from `DomainEvent`'s `@JsonSubTypes`**, so a raw
  `message-received` event published on the Kafka `upstream` topic could not be deserialized.
  It is now registered as `message-received`.
- **Startup failure on databases with existing workflow definitions**: the new NOT NULL
  `default_max_step_executions` column could not be added by `ddl-auto=update` (Postgres rejects
  it without a default). `@ColumnDefault("0")` fixes Hibernate-managed schemas and a V4 Flyway
  migration covers Flyway-managed deployments; `0` = unbounded.
- **Process detail view**: explicit tab names keep *steps*, *log*, *errors* and *resources* as
  separate tabs (consecutive bare `@Tab` fields now merge into one tab in Mateu), and
  `CreateProcessForm` marks the form clean on create — creating *is* the save, so no
  "save before leaving?" prompt.
- **Workflow definition editor**: removed the backward-compatible `WorkflowDefinition`/`Step`
  constructors that made Mateu's `ReflectionInstanceFactory` build empty objects on save
  (`name=null`, `steps=[]`), and the precondition/compensation step lookups now exclude the step
  being edited, so a step can no longer be offered as its own precondition or compensation.

## [1.0-beta.010] - 2026-07-24

### Fixed
- **Kafka broker resilience.**
  - Corrected the default Kafka broker address to `localhost:9092` (was a `9192` typo), so
    a standalone app started from the IDE without `KAFKA_BROKERS` set connects to the local
    dev broker instead of rebootstrapping in an endless loop.
  - Standalone apps now **boot gracefully when the broker is unavailable at startup**. The
    Spring Cloud Stream binder's provisioning/admin timeouts are bounded and binding retry
    is enabled, so the context no longer blocks ~2 minutes (and loses its AdminClient) — it
    starts promptly and binds its consumers as soon as the broker is up.

### Added
- **Distributed chaos tests for Kafka broker outages** (`workflow-dist-e2e`): a process
  recovers when the broker disappears mid-flight and returns — driven by the transactional
  outbox (DIST-06) — and the orchestrator boots and processes normally when the broker is
  unavailable at startup and later returns (DIST-07).

## [1.0-beta.009] - 2026-07-23

### Added
- **Workflow definition lifecycle management** in the admin UI. Definitions move
  through `DRAFT` → `ACTIVE` → `DISABLED` → `ARCHIVED` with per-status toolbar
  actions: *Promote to production* (working copies only), *Create working copy*,
  *Disable*/*Enable*, *Archive* and *Reactivate*. An `ACTIVE` definition is
  read-only (edited through a working copy) and must be disabled before it can be
  archived; *Reactivate* returns an archived definition to `DRAFT`. New
  definitions are created as `DRAFT` and the status is never editable. Documented
  with a state diagram in `/guides/workflow-definitions`.
- EventConductor now **owns the workflow graph component** (moved out of mateu),
  served as a self-contained web component from the engine jar.

### Changed
- **Migrated to Spring Boot 4** (from 3.5): starter/autoconfig relocations,
  Jackson 3, Spring Cloud 2025.1, Spring AI 2.0 and networknt
  json-schema-validator 3.0.
- Adopted **mateu 3.0-alpha.263** (new `CrudStore` API). This brings the UI
  behaviour the lifecycle work relies on: the top navigation renders as a Vaadin
  menu-bar, an intermediate menu route (e.g. `/workflow`) shows a section index
  instead of "Not found", and the built-in *Edit* action is hidden on `ACTIVE`
  definitions.
- Coordinated **gRPC 1.83.0 + protobuf 4.35.1** bump; commons-jexl3 3.7.0; and
  Spring Cloud dependency updates.

## [1.0-beta.008] - 2026-07-17

### Added
- `workflow-maven-plugin`: a Maven plugin (goal `eventconductor:validate`, bound
  to `process-resources`) that validates workflow, form and rule definitions
  (JSON/YAML) against the engine's published specifications at build time and
  fails the build on any violation. It bundles the canonical schemas straight
  from the sibling engine modules so it can never drift, and adds the semantic
  checks a schema cannot express: workflow duplicate/dangling/self-referencing
  step references, cron-expression validity and JEXL parseability of
  preconditions; decision-table row arity and JEXL parseability of rule
  expressions. Configurable directories, per-type toggles, `failOnError`,
  `failOnMissing` and `skip`.

## [1.0-beta.007] - 2026-07-16

### Added
- `RULE` step type backed by a new rule-engine catalog and embeddable
  rule-runtime: a step references a `ruleId` and the engine dispatches it for
  evaluation.
- Forms **Tasks v2**: the pending-tasks listing renders the completion form
  inline in each row, with an optimized query behind it.
- `TIMER` step type: durably pauses a process for an ISO 8601 duration
  (`duration: "PT72H"`) or until an absolute date taken from a process variable
  (`untilVariable`), fired by the timeout scheduler in both memory and JPA modes.
- `MESSAGE` step type with correlation: a step waits (durably, dispatching no
  task) for a `MessageReceived` correlated by the process `businessKey` or a
  `correlationExpression`; the message variables merge into the process.
- Cron-scheduled process starts: a definition can declare a `cronExpression`
  and the engine creates instances at each occurrence with deterministic
  business keys so multiple pods never duplicate an occurrence. New
  `workflow.cron-scan-interval-ms` and `workflow.cron-enabled` properties.
- Built-in process analytics (`ProcessAnalyticsService`): per definition and
  time window, instance counts by status, completion/error/cancellation rates,
  throughput per day and average/p95 durations per process and per step with a
  bottleneck flag — in all deployment modes. Surfaced in an **Analytics** UI
  page and the `getWorkflowAnalytics` / `findBottleneck` MCP tools. Step
  executions record `finishedAt` on terminal status (Flyway `V3`).
- Public test specification ([TESTING.md](./TESTING.md)) and the matching
  suites: an embedded/JPA end-to-end suite (`workflow-e2e`) covering
  orchestration semantics, failure handling, timers, message correlation,
  cron starts, idempotency, the JEXL sandbox and analytics; a JPA durability
  suite driving the real outbox relay and JDBC advisory locks on H2; a
  single-JVM crash-recovery test; and a Docker-gated distributed suite
  (`workflow-dist-e2e`, `dist-e2e` profile) over real Kafka + PostgreSQL via
  Testcontainers — distributed happy path, crash recovery, two-pod dispatch
  exclusivity, worker-crash redelivery and a 500-instance load smoke.

### Changed
- Bumped the Mateu UI dependency to `3.0-alpha.243` and centralized it as a
  `mateu.version` property.

### Fixed
- A process whose step exhausted its retries is now reported as `ERROR`
  instead of being falsely marked `COMPLETED`, and successors of a failed
  step no longer run.
- Precondition (JEXL) expressions are evaluated in a sandbox
  (`JexlPermissions.RESTRICTED`, blocking reflection and system access) and
  now fail closed — an evaluation error means the guarded step does not run.
- Outbox relays publish before marking a message `Sent` (at-least-once);
  messages that cannot be loaded are parked as `Error` rather than retried
  forever, and message types are validated against an `io.mateu.*` allowlist.
- Process creation deduplicates by `businessKey`, so a redelivered creation
  event no longer creates a duplicate process.
- Cancellation marks the process `CANCELLED` first so the orchestration loop
  cannot dispatch new steps mid-cancellation, and late worker reports for
  steps already in a terminal state are ignored.
- Step timeouts now fire in memory-persistence mode, not only in JPA mode.
- Definition validation rejects duplicate step ids and dangling
  `preconditionStepId` / `compensationStepId` references.
- Relay and scheduler threads are daemon threads with configurable poll
  intervals.

## [1.0-beta.006] - 2026-07-02

### Added
- Spring Boot Actuator with Prometheus, health, and info endpoints in
  `workflow-engine` and `forms-engine`.
- Custom Micrometer metrics for processes by status, step executions and
  retries.
- Spring Security with HTTP Basic on REST endpoints, configurable via
  application properties. Actuator health/info remain public.
- Flyway migrations for the workflow and forms schemas. Default `ddl-auto`
  is now `validate` in production profiles.
- Logstash-encoder structured JSON logging via `application-prod.yaml`.
- LICENSE (MIT), SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md and
  CHANGELOG.md.
- Dependabot configuration for Maven, GitHub Actions, Docker and npm.
- Build status, CodeQL and license badges in the README.
- Helm chart hardening: external secrets, NetworkPolicy, PodDisruptionBudget,
  Ingress template and Actuator-based probes.
- New PR CI workflow that runs `mvn verify` (unit + integration tests +
  JaCoCo coverage check) and Trivy container scans.
- Renamed misspelled `buid-and-publish.yml` to `build-and-publish.yml` and
  enabled running the test suite during release builds.

### Changed
- `workflow.mode` now defaults to `embedded` (previously `kafka`), mirroring
  `workflow.persistence` which defaults to `memory`. Apps start fully
  in-process with no external dependencies and opt into JPA/Kafka as they
  scale. The standalone distributed apps set `kafka`/`jpa` explicitly.
- Bumped the Mateu UI dependency to `3.0-alpha.222`.
- Dockerfiles run as a non-root `app` user (UID 10001).
- `docker-compose.yml` split into `docker-compose.dev.yml` (with default
  passwords) and `docker-compose.yml` (with required env vars).

### Fixed
- Quote the reserved SQL column `values` in `FormExecutionEntity` so schema
  creation succeeds on H2 and PostgreSQL.

### Security
- All REST endpoints in the orchestrator and forms standalone apps now
  require authentication by default.
- Bumped Eclipse JGit to `6.10.1.202505221210-r` to fix the XXE vulnerability
  CVE-2025-4949 (GHSA-vrpq-qp53-qv56).

## [Earlier]

EventConductor pre-1.0 snapshots. See the `git log` for individual commits.
