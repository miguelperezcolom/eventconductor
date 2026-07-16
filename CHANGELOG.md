# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0-beta.006] - 2026-07-16

### Added
- Public test specification ([TESTING.md](./TESTING.md)) and the matching suites:
  an embedded/JPA end-to-end suite (`workflow-e2e`) covering orchestration
  semantics, failure handling, timers, message correlation, cron starts,
  idempotency, the JEXL sandbox and analytics; a JPA durability suite driving
  the real outbox relay and JDBC advisory locks on H2; a single-JVM
  crash-recovery test; and a Docker-gated distributed suite (`workflow-dist-e2e`,
  `dist-e2e` profile) exercising real Kafka + PostgreSQL via Testcontainers —
  distributed happy path, crash recovery, two-pod dispatch exclusivity,
  worker-crash redelivery, and a 500-instance load smoke.
- Built-in process analytics: `ProcessAnalyticsService` computes, per workflow
  definition and time window, instance counts by status, completion/error/
  cancellation rates, throughput per day, average/p95 process duration and
  average/p95 duration per step with the slowest step flagged as bottleneck —
  in all deployment modes. Surfaced in a new **Analytics** UI page and two MCP
  tools (`getWorkflowAnalytics`, `findBottleneck`). Step executions now record
  `finishedAt` when they reach a terminal status (Flyway `V3` migration).
- `TIMER` step type: durably pauses a process for an ISO 8601 duration
  (`duration: "PT72H"`) or until an absolute date taken from a process variable
  (`untilVariable`), fired by the existing timeout scheduler infrastructure in
  both memory and JPA modes.
- Cron-scheduled process starts: a workflow definition can declare a
  `cronExpression` (Spring syntax) and the engine creates process instances at
  each occurrence, with deterministic business keys so multiple pods never
  duplicate an occurrence. New `workflow.cron-scan-interval-ms` and
  `workflow.cron-enabled` properties.
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
- Dockerfiles run as a non-root `app` user (UID 10001).
- `docker-compose.yml` split into `docker-compose.dev.yml` (with default
  passwords) and `docker-compose.yml` (with required env vars).

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

### Security
- All REST endpoints in the orchestrator and forms standalone apps now
  require authentication by default.

## [Earlier]

EventConductor pre-1.0 snapshots. See the `git log` for individual commits.
