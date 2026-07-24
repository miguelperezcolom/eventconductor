# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
