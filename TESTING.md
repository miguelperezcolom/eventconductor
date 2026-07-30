# EventConductor — Test Specifications

This document defines the automated test suite that validates EventConductor's behavior as a
workflow engine, with emphasis on the guarantees an evaluation against Camunda / Temporal
cares about: **correct orchestration semantics, failure handling, durability, and concurrency
safety**.

## Test levels

| Level | Module | Infrastructure | Runs in CI |
|---|---|---|---|
| Unit | `modules/*/src/test` | None (Mockito / plain objects) | Yes (`mvn verify`) — ~390 tests |
| End-to-end (embedded) | `modules/workflow-e2e` | None — engine in `embedded` + `memory` mode, programmable workers | Yes (`mvn verify`) — ~33 tests, all green |
| End-to-end (JPA outbox) | `modules/workflow-e2e` | H2 in PostgreSQL mode — exercises the real outbox relay, JDBC advisory locks and JPA persistence | Yes (`mvn verify`) — 4 tests (see §5) |
| Distributed (Kafka) | `modules/workflow-dist-e2e` (Testcontainers) | PostgreSQL + Kafka in Docker | Yes — dedicated CI job via the `dist-e2e` Maven profile — 8 tests (see §6) |

The e2e tests drive the engine exclusively through its public surface: upstream events
(`ProcessCreationRequested`, worker status callbacks), the `EmbeddedTaskExecutor` port, and the
repositories for assertions. No engine internals are mocked.

## 1. Core orchestration (E2E, embedded + memory)

| ID | Spec |
|----|------|
| E2E-SEQ-01 | **Sequential happy path.** Given a 3-step sequential definition, when a process is created, then steps execute strictly in order, each receives the accumulated process variables, the process ends `COMPLETED` with `completionPercentage=100` and `finished` set. |
| E2E-SEQ-02 | **Variable propagation.** Variables written by a worker on step N are visible to step N+1 and stored on the process. |
| E2E-PAR-01 | **Parallel fan-out.** Steps marked `parallel: true` are all dispatched without waiting for each other; the process completes when all finish. |
| E2E-END-01 | **END step semantics.** Reaching an END step completes the process and cancels any remaining non-terminal steps. |
| E2E-COND-01 | **JEXL branching (true).** A step guarded by `preconditionExpression` runs when the expression evaluates truthy against process variables. |
| E2E-COND-02 | **JEXL branching (false).** The guarded step does not run when the expression is falsy; the process still completes via the END step. |
| E2E-COND-03 | **Fail-closed guards.** A step whose precondition references a missing variable (evaluation error) must NOT run. |
| E2E-PRE-01 | **preconditionStepId gating.** A step with a `preconditionStepId` only runs after that step is COMPLETED. |
| E2E-ANA-01 | **Built-in analytics.** After running completed and failing processes, the analytics service reports exact instance counts by status, completion/error rates, created-per-day series, per-step execution counts and durations, and flags exactly one step as the bottleneck (`AnalyticsE2eTest`). |

## 2. Failure handling (E2E, embedded + memory)

| ID | Spec |
|----|------|
| E2E-RET-01 | **Automatic retry.** A step with `retries: 2` whose worker fails twice then succeeds ends COMPLETED; the process completes; `attemptCount` reflects the retries. |
| E2E-RET-02 | **Retries exhausted → process ERROR.** When a step exhausts its retries, the step ends `ERROR`, successors do NOT run, and the process ends `ERROR` (never falsely COMPLETED). |
| E2E-RET-03 | **Manual step retry.** Retrying a failed step (operator action) resets it, re-dispatches it and — if it then succeeds — the process resumes and completes. |
| E2E-RET-04 | **Manual process retry.** `RetryProcessUseCase` resets all ERROR/TIMEOUT steps and resumes the flow. |
| E2E-RET-05 | **Retry cannot revive a cancelled process.** Retrying a failed step of a CANCELLED process is a no-op. |
| E2E-COMP-01 | **Compensation (saga).** A `rollbackable` step that exhausts retries triggers its `compensationStepId` step; the compensation executes and the process ends `ERROR`. |
| E2E-TIME-01 | **Step timeout.** A step with `timeout` whose worker never responds transitions to `TIMEOUT` via the timeout scheduler (works in memory mode too), the worker receives `TaskCancellationRequested`, and retry/failure semantics engage. |
| E2E-CANC-01 | **Cancellation mid-flight.** Cancelling a RUNNING process marks it CANCELLED first, cancels non-terminal steps, publishes `TaskCancellationRequested` for in-flight ones, and never dispatches new steps during cancellation. |
| E2E-CANC-02 | **Late worker report after cancellation.** A `COMPLETED` report arriving for an already-CANCELLED step is ignored (terminal-state guard). |
| E2E-USER-01 | **User task.** A `USER_TASK` step dispatches a `complete-form` task carrying `formId` as a variable; completing it advances the flow. |
| E2E-USER-02 | **User task without formId fails visibly.** The step ends ERROR through the normal failure pipeline (process ends ERROR after retries), never freezing the process. |

## 3. Timers, messages and scheduled starts (E2E, embedded + memory)

| ID | Spec |
|----|------|
| E2E-TIMER-01 | **Timer step (duration).** A `TIMER` step with an ISO 8601 `duration` (e.g. `PT0.5S`) durably pauses the process: no task is dispatched for it, successors do not run while it is PENDING, and once the duration elapses the timer scheduler completes the step and the flow resumes to COMPLETED. |
| E2E-TIMER-02 | **Timer step (absolute date from variable).** A `TIMER` step with `untilVariable` fires when the ISO 8601 date-time carried by that process variable passes; the process completes only after that moment. |
| E2E-TIMER-03 | **Misconfigured timer fails visibly.** A `TIMER` step whose `untilVariable` is missing from the process ends ERROR through the normal failure pipeline (process ends ERROR), never freezing the process. |
| E2E-MSG-01 | **Message correlation happy path.** A `MESSAGE` step durably pauses the process: no task is dispatched for it and successors do not run while it waits. A `MessageReceived` with the step's `messageName` and the process `businessKey` as correlation key completes the step, merges the message variables into the process (visible to successors) and the flow resumes to COMPLETED. |
| E2E-MSG-02 | **Correlation mismatch does not advance.** A message with the right name but a non-matching correlation key leaves the step PENDING and dispatches nothing; a later correctly-correlated message still gets through. |
| E2E-MSG-03 | **Timeout on a waiting MESSAGE step.** A `MESSAGE` step with `timeout` that receives no message transitions to `TIMEOUT` via the timeout scheduler and the normal retry/failure semantics engage (retries: 0 → process ERROR). |
| E2E-MSG-04 | **Correlation by JEXL expression.** A step with `correlationExpression` correlates by the expression's value over process variables instead of the businessKey (which no longer matches); fail-closed like preconditions. |
| E2E-MSG-05 | **Messages are ignored, not buffered.** A message arriving when no step is waiting for it is dropped: a process created afterwards still waits, and only a redelivery resumes it. This is the documented delivery contract (at-least-once upstream: the sender retries). |
| E2E-CRON-01 | **Cron-scheduled process starts.** An ACTIVE definition with a `cronExpression` gets process instances created automatically at each occurrence; business keys are derived deterministically from the occurrence time (no duplicate instances per occurrence) and the instances run to COMPLETED. |

## 4. Idempotency, consistency, security

| ID | Spec |
|----|------|
| E2E-IDEM-01 | **Duplicate creation events.** Two `ProcessCreationRequested` deliveries with the same `businessKey` produce exactly one process. |
| E2E-IDEM-02 | **Duplicate dispatch.** A duplicate `TaskExecutionRequested` for a step already past PENDING is ignored (worker executes once). |
| E2E-IDEM-03 | **Duplicate message delivery.** Two identical `MessageReceived` deliveries complete the waiting `MESSAGE` step exactly once (terminal-state guard under the process lock); the successor executes once and the process completes normally. |
| UNIT-VAL-01 | **Definition validation.** Definitions with duplicate step ids, dangling `preconditionStepId` or dangling `compensationStepId` are rejected by `checkInvariants`. |
| E2E-SEC-01 | **JEXL sandbox.** A precondition attempting `''.getClass().forName('java.lang.Runtime')…` (or any reflection/System access) does not execute code and — being an evaluation error — the guarded step does not run. |
| UNIT-SEC-02 | **Webhook signature.** Malformed HMAC hex in `X-Hub-Signature-256` yields 401 (not 500); missing signature when a secret is configured yields 401. |
| UNIT-OUT-01 | **Outbox message-type allowlist.** Outbox rows whose `messageType` is not an `io.mateu.*` class are parked as `Error`, never loaded. |

## 5. Durability (E2E, embedded + JPA/H2)

Runs the engine with `workflow.persistence=jpa` against H2 (PostgreSQL mode), exercising the
real `outbox_message_entity` table, the `EmbeddedOutboxRelay` poll loop and the JDBC-backed
repositories and advisory locks. `AbstractJpaE2eTest` is the async-aware harness (events are
relayed, so tests await terminal state); each method runs in a fresh context because these
tests share real DB + relay threads + JVM-static H2 locks.

| ID | Spec | Status |
|----|------|--------|
| E2E-JPA-01 | **Happy path through the outbox.** Every domain event flows through the outbox table and the relay; the process completes and all of this run's outbox messages end `Sent` (none `Pending`, none `Error`). | ✅ `JpaDurabilityE2eTest` |
| E2E-JPA-03 | **Failure semantics on JPA.** Retries exhausted → step `ERROR`, process `ERROR` — identical to memory mode (memory/JPA state-machine parity). | ✅ `JpaDurabilityE2eTest` |
| E2E-OUT-POISON | **Outbox poison message.** A row whose `messageType` is not an `io.mateu.*` class is parked as `Error`, never retried forever (validates the allowlist added during the audit). | ✅ `JpaDurabilityE2eTest` |
| E2E-CRASH-01 | **Crash recovery / durable resume.** Node A (relay disabled) creates a process whose `ProcessCreated` event is durably parked in the outbox, then "crashes" (context closed). Node B boots against the same database with the relay enabled and drives the process to completion — no work lost. Two real Spring contexts over one file-based H2. | ✅ `CrashRecoveryE2eTest` |

## 6. Distributed suite (implemented — `modules/workflow-dist-e2e`)

Runs the real distributed topology with Testcontainers: one PostgreSQL and one Kafka
container, orchestrator "pods" booted as separate Spring contexts in `kafka` + `jpa` mode
(each with its own Kafka consumers, connection pool, outbox relay and background threads)
and a programmable Kafka worker (`WorkerStub`) consuming `downstream` / reporting on
`upstream` — the same wiring as `apps/orchestrator-standalone-app`. Topics get 6 partitions
so two pods in the `orchestrator-group` genuinely share event traffic. Processes are created
by publishing `ProcessCreationRequested` JSON onto the `upstream` topic, exactly like an
external producer; assertions read PostgreSQL directly so they survive pod kills. The module
is excluded from the default build (needs Docker); it runs under the `dist-e2e` Maven
profile and a dedicated CI job.

| ID | Spec | Status |
|----|------|--------|
| DIST-01 | **Kafka mode happy path.** Orchestrator + Kafka worker: a process created through the upstream topic completes; each step is executed exactly once by the worker; every domain event flows through the outbox and ends `Sent`. | ✅ `Dist01KafkaHappyPathTest` |
| DIST-02 | **Crash recovery (distributed).** Kafka + Postgres variant of E2E-CRASH-01: the orchestrator is killed after a step's completion is committed but before the next dispatch (the test holds the relay's advisory lock, so the domain event is durably parked `Pending` — the exact state a crash between commit and publish leaves). A fresh pod resumes from the outbox and completes the process. | ✅ `Dist02CrashRecoveryTest` |
| DIST-03 | **Two orchestrator pods.** Both consume events for the same processes (multi-partition topics, shared consumer group); advisory locks guarantee each step is dispatched exactly once — asserted on worker-side execution counts across 20 concurrent processes. | ✅ `Dist03TwoOrchestratorsTest` |
| DIST-04 | **Worker crash / redelivery.** A worker that dies mid-task (takes the task, never reports): the step times out (3 s), is retried, the second execution succeeds and the process completes. | ✅ `Dist04WorkerCrashTest` |
| DIST-05 | **Load smoke.** N=500 concurrent processes (3 ACTION steps each → 1,500 task executions) through two orchestrator pods complete within the 300 s bound; no lost or stuck instances. Measured: **11.3 s wall clock → 44.1 process instances/second** (engine-side window 8.4 s → 59.7 PI/s, first creation → last completion) on an Apple M3 Max with default settings — the baseline published in the comparison guide. | ✅ `Dist05LoadSmokeTest` |
| DIST-06 | **Kafka broker outage mid-flight.** With consumers already bound, the broker container is stopped while a process is held mid-flight; events park in the transactional outbox and, once the broker returns, the process reaches COMPLETED with the outbox fully drained. | ✅ `Dist06KafkaOutageRecoveryTest` |
| DIST-07 | **Kafka down at startup.** With bounded binder admin/request timeouts and binding retry (the config now in the standalone apps), the orchestrator boots promptly while the broker is paused; once the broker resumes the consumers bind and a process created through the upstream topic completes. | ✅ `Dist07KafkaDownAtStartupTest` |
| DIST-08 | **PostgreSQL down at startup.** With DB-resilient settings (lazy pool, `ddl-auto: none`, explicit dialect), the pod boots without a database; once PostgreSQL is reachable it drives a process parked mid-flight (Pending outbox row — the DIST-02 crash window) to completion and runs brand-new processes end to end. | ✅ `Dist08PostgresDownAtStartupTest` |

## How to run

```bash
# Everything that CI runs (unit + e2e):
mvn -B -ntp verify

# Only the e2e suite:
mvn -pl modules/workflow-e2e -am verify

# Distributed suite (needs Docker; PostgreSQL + Kafka via Testcontainers):
mvn -Pdist-e2e -pl modules/workflow-dist-e2e -am verify
```

The e2e suite configures fast polling (`workflow.outbox-poll-interval-ms`,
`workflow.timeout-scan-interval-ms`, `workflow.cron-scan-interval-ms`) so the whole suite
stays in the tens of seconds. Cron starts are disabled suite-wide
(`workflow.cron-enabled=false`) and re-enabled only in `CronStartE2eTest`'s own context,
so the every-second `cron.json` definition does not spawn processes in unrelated tests.
