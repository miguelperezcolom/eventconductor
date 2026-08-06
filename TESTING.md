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
| Browser (UI) | `modules/workflow-ui-e2e` (Playwright) | A real Chromium, downloaded on first run | Yes — dedicated CI job via the `ui-e2e` Maven profile — 14 journeys (see §7) |

The e2e tests drive the engine exclusively through its public surface: upstream events
(`ProcessCreationRequested`, worker status callbacks), the `EmbeddedTaskExecutor` port, and the
repositories for assertions. No engine internals are mocked.

## Coverage, and what the number means

The JaCoCo gate is a **floor per module**, set just under what that module measures today and
raised as it improves. It is not a target, and it is deliberately not one number for the repository.

| Module | Floor | Measured |
|---|---|---|
| `rule-runtime` | 0.87 | 89.2% |
| `workflow-maven-plugin` | 0.87 | 89.5% |
| `shared` | 0.85 | 87.9% |
| `workflow-engine` | 0.62 | 64.7% alone — **81.1%** with the e2e module merged in |
| `forms-engine` | 0.42 | 44.0% |
| `rule-engine` | 0.40 | 42.8% |

**Read those numbers with two things in mind.**

*The gate used to measure far less than it appeared to.* It asked for 85% of lines, and passed,
over a bundle that excluded the outbox, the schedulers, the JPA repositories, the message REST API,
the MCP tools, the autoconfiguration and the Git import — most of what carries risk in production.
The exclusion list is now two entries: generated protobuf/gRPC stubs, and the Vaadin view classes
whose behaviour is the framework's rendering. Everything else is measured. Over that honest scope
the repository sits at **65.7%** per module, **76.4%** aggregated.

*Per-module figures undercount the engine.* JaCoCo attributes coverage to the module whose tests
ran, and the 33 end-to-end tests live in `modules/workflow-e2e`, so everything they exercise in
`workflow-engine` — the relay, the state machine, compensation, timers — counts for neither. The
aggregated figure is the true one:

```bash
mvn -B -ntp test
java -jar ~/.m2/repository/org/jacoco/org.jacoco.cli/0.8.13/org.jacoco.cli-0.8.13-nodeps.jar \
  merge modules/*/target/jacoco.exec --destfile /tmp/merged.exec
java -jar ~/.m2/repository/org/jacoco/org.jacoco.cli/0.8.13/org.jacoco.cli-0.8.13-nodeps.jar \
  report /tmp/merged.exec --classfiles modules/workflow-engine/target/classes --html /tmp/coverage
```

Coverage is a floor against regression, not evidence of correctness. What the engine actually
guarantees is specified below and in the reliability and scale runs under
`modules/workflow-benchmark/k8s`, not by any percentage here.

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
| E2E-MSG-06 | **A correlation key that moves while the step waits.** The key is materialised on the step execution so an arriving message can be matched by index. A parallel branch that writes the very variable the `correlationExpression` reads moves it: the key armed at start stops matching and the new one correlates. Correlation reads the process as it is now, not as it was when the wait began. |
| E2E-CRON-01 | **Cron-scheduled process starts.** An ACTIVE definition with a `cronExpression` gets process instances created automatically at each occurrence; business keys are derived deterministically from the occurrence time (no duplicate instances per occurrence) and the instances run to COMPLETED. |

## 4. Idempotency, consistency, security

| ID | Spec |
|----|------|
| E2E-IDEM-01 | **Duplicate creation events.** Two `ProcessCreationRequested` deliveries with the same `businessKey` produce exactly one process. |
| E2E-IDEM-02 | **Duplicate dispatch.** A duplicate `TaskExecutionRequested` for a step already past PENDING is ignored (worker executes once). |
| E2E-IDEM-03 | **Duplicate message delivery.** Two identical `MessageReceived` deliveries complete the waiting `MESSAGE` step exactly once (terminal-state guard under the process lock); the successor executes once and the process completes normally. |
| E2E-LOCK-01 | **Stale process write rejected.** A writer holding a copy read before another committed is rejected with an optimistic-locking failure instead of silently overwriting. This is the fence for the one hole in Kafka's ownership guarantee: a consumer group assigns a partition to one consumer, but during a rebalance the outgoing pod can still be in flight on a record the incoming one now owns. |
| E2E-LOCK-02 | **Stale step-execution write rejected.** The same for the other aggregate a running process mutates. |
| E2E-LOCK-03 | **A never-persisted aggregate still inserts.** The version doubles as Spring Data's "never persisted" signal, so getting it wrong turns every creation into an update of a row that is not there. |
| E2E-OPS-01..05 | **Operator actions travel as events.** Pause, resume, cancel, retry-process and retry-step are published keyed by the process and carried out by the pod that owns it, instead of running wherever the UI click or MCP call landed. Six handlers that nothing else exercises; the routing itself is covered by the keys in DIST-11. |
| E2E-OPS-06 | **Restart runs the succeeded steps again.** A restart request re-runs the whole process from the top: a step that had already completed is invoked a second time, which is what separates a restart from a retry. |
| E2E-OPS-07 | **A cancelled process can be picked up again.** Retry from failure revives the cancelled steps — cancellation is what every unfinished step was set to — and the process runs to completion. |
| E2E-OPS-08 | **A cancelled process can be restarted from the beginning**, the same as a failed one. A process that is still RUNNING is refused by both, in the use case rather than in the button, because the list applies them to a selection. |
| E2E-GUARD-01 | **A condition on one incoming link.** A step whose guarded link's condition holds runs, and the process completes. The condition is written on the link, not on the step, so it says when arriving *by that route* counts. |
| E2E-GUARD-02 | **A false link condition holds the step.** The step the link names has COMPLETED and the other link is clear, yet the step does not run: "wait for all of them" is read literally, so an unsatisfied link waits rather than being dropped. |
| E2E-GUARD-03 | **The held step is not tidied away.** The engine's implicit completion — cancel what is left and finish, when nothing can run — must not fire around a step held by a link condition, or the hold would silently become a cancellation. The process stays RUNNING. |
| E2E-GUARD-04 | **The step-level `preconditionExpression` still skips**, rather than holding, so every definition written before links could carry conditions keeps its behaviour. |
| WD-STATUS-01 | **One status, two sources.** A workflow is open for business only if both its definition and the runtime say so, and the stricter of the two wins. Clearing the runtime status does not lift a declaration — the file is a floor. |
| WD-STATUS-02 | **A re-import keeps the operator's decision** and replaces only the declaration, instead of putting back into service whatever had been disabled. |
| WD-STATUS-03 | **Creation is refused** for a workflow closed by either source — the path the cron scheduler checked and process creation did not. |
| WD-STATUS-04 | **The older spellings still read.** `disabled: true` and `archived: true` in a file, and a legacy `status: DRAFT/ACTIVE`, are adopted into the one status; a file using them does not parse without that adoption. |
| E2E-DLQ-01 | **Embedded mode parks what it cannot process.** With no dead-letter topic, the outbox table is the queue and its `Error` status is the resting place — visible, and replayable by putting the row back to `Pending`. The alternative is not "dropped" but "retried every cycle for ever", so a healthy process is asserted to keep running alongside a parked message. |
| UNIT-ROUTE | **Unkeyed reports are re-routed, not handled locally.** In kafka mode a worker that does not echo the process leaves its report unkeyed, so it can land on a pod that does not own it — the last way two pods reach the same process once the lock is gone. `UnkeyedEventRouterTest` pins that such a report is re-published carrying the process, that a keyed one is left alone, and that nothing happens outside kafka mode. |
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
| DIST-09 | **Concurrent outbox claim.** Two sessions claim batches of pending outbox messages at the same time: both get a full batch, the batches do not overlap, neither waits on the other, and a third finds nothing left to claim. The relay is no longer a leader-elected singleton, so relay throughput grows with the cluster; this must run on real PostgreSQL, since H2 locks every row the claim matches rather than only those it returns and a second claimer there comes back empty. |
| DIST-10 | **Concurrent processes on a small connection pool.** 40 processes run through a pod whose pool holds 3 connections, and all complete. Per-process exclusion is a row lock held by the transaction the work already runs in, so a critical section costs one connection; when it was an advisory lock it cost two — the lock's own session plus the work's — and the pool, not the database, capped concurrency, with a wedge rather than a slowdown past the limit. Verified to discriminate: restoring the two-connection shape exhausts the pool and the test times out. |
| DIST-11 | **Partition ownership.** Every event of a process carries that process as its Kafka key, so all of them hash to one partition — which a consumer group hands to exactly one pod. Read off the topics directly rather than inferred: a key that silently fails to be written leaves every event round-robining as before, and nothing else would look different. This is what per-process serialization and ordering rest on. |
| DIST-12 | **An unprocessable event is parked, not dropped, and does not stall the traffic around it.** Real traffic is driven with a report for a step execution the engine has never heard of mixed into it: the real processes finish, and the poison event turns up on the dead-letter topic unchanged. Both halves matter — a poll batch shares transactions, so the scope has to be one process; and an event the engine gives up on has to be visible somewhere. |

## 7. The UI, in a browser (`modules/workflow-ui-e2e`)

Everything above drives the engine through its ports. These drive it the way an operator does:
a real browser, a real click, a real wait for the screen to catch up.

**Playwright rather than Selenium**, because the UI is nested *open* shadow roots four deep
(`mateu-ui` → `mateu-ux` → `mateu-app` → `vaadin-*`). Playwright's selector engines pierce open
shadow roots; with WebDriver every step would mean walking `shadowRoot` by hand.

**Selectors are text, isolated behind page objects.** The UI exposes no test hooks — Mateu emits a
fresh UUID as every component's id on every render — so there is nothing stable to select by except
custom element names and the words on screen. Selecting by visible text is the right thing for a
test that claims to simulate a user, and it does couple these tests to the UI's copy: keeping every
such selector in `io.mateu.workflow.uie2e.pages` makes a renamed button one edit instead of many,
and is where `data-testid` would land if the framework grows them.

**Assertions wait, they never sample.** An operator action does not perform anything — it publishes
a request that the pod owning the process carries out, after which the detail view's two-second
poll notices. So every assertion is on what the badge *becomes*.

| ID | Spec | Status |
|----|------|--------|
| UI-NAV-01 | The Workflow menu offers Definitions, Processes, Steps and Analytics — the engine's navigation contract with any app that embeds it. | ✅ |
| UI-NAV-02 | A started process appears in the list, under its workflow's name. | ✅ |
| UI-NAV-03 | The list is columned Id / Name / Status / Created / Started / Finished. | ✅ |
| UI-NAV-04 | A process that completes is *shown* as Completed, not merely recorded as such. | ✅ |
| UI-NAV-05 | Search narrows the list. Asserted on the grid's own item count: `vaadin-grid` reuses its cell elements, so a filtered-out row is still in the DOM holding its old text. | ✅ |
| UI-DET-01 | Opening a process shows its id, name and outcome. | ✅ |
| UI-DET-02 | The detail offers all six tabs: Diagram, Steps, Messages, Errors, Resources, Variables. | ✅ |
| UI-DET-03 | The diagram draws the steps of the definition — the one part of this UI with no equivalent in the API. | ✅ |
| UI-DET-04 | The Steps tab lists what ran, by step id. | ✅ |
| UI-DET-05 | A rolled-back saga shows COMPENSATED, its compensation step, and the reason in the Errors tab. | ✅ |
| UI-DET-06 | "Back to list" returns to the list with the process still in it. | ✅ |
| UI-OPS-01 | Pausing a running process stops it — the click reaches the engine and the badge becomes Paused. | ✅ |
| UI-OPS-02 | Cancelling asks first and only acts on confirmation. | ✅ |
| UI-OPS-03 | A running process is offered Pause and Cancel, and *not* Resume. | ✅ |
| UI-OPS-04 | Resuming a paused process sets it running again. | ⛔ Disabled — the detail never renders "Resume process" (see below) |
| UI-OPS-05 | "Retry from failure" re-drives a rolled-back process. | ⛔ Disabled — the detail never renders it |
| UI-OPS-06 | A paused process is offered Resume. | ⛔ Disabled — same cause |

### What these found

**Three of the five operator actions are missing from the process detail.**
`SimpleProcessViewModel` declares cancel, pause, resume, retry and restart, all `@Toolbar` and none
`@Hidden`. Only cancel and pause are ever rendered — in either state of the "⋯" expander, at any
window width, and whatever the process status. Confirmed by hand against
`testbench/workflow-embedded`, outside the tests. An operator therefore cannot resume, retry or
restart from the screen showing the process that needs it, which is precisely what `retryProcess`'s
javadoc says it is for. The list page offers Retry and Restart against a selection, which is the
workaround. The three specs above are written and disabled; they should pass unchanged once the
toolbar renders them.

**The engine did not apply its own schema in this application.**
`WorkflowSchemaAutoConfiguration` loaded but its initializer was conditioned away —
`@ConditionalOnSingleCandidate(DataSource.class)` found no bean at evaluation time even though
`DataSourceAutoConfiguration` matched, meaning it is being ordered ahead of the data source rather
than after it. The engine's own unmanaged-schema warning fired and `ddl-auto=validate` then failed
on the missing tables, which is the sequence that warning exists to produce instead of silence.
`UiE2eApplication` declares the initializer explicitly as a documented workaround so these tests
still run on the real migrations.

### Running them

```bash
mvn -Pui-e2e -pl modules/workflow-ui-e2e -am verify
```

The first run downloads a Chromium (~150 MB). Every test leaves a screenshot and the rendered HTML
under `target/ui-e2e/`, passing or failing — a browser test that fails in CI is unreadable from a
stack trace, and the question is always "what was on screen?".

## How to run

```bash
# Everything that CI runs (unit + e2e):
mvn -B -ntp verify

# Only the e2e suite:
mvn -pl modules/workflow-e2e -am verify

# Distributed suite (needs Docker; PostgreSQL + Kafka via Testcontainers):
mvn -Pdist-e2e -pl modules/workflow-dist-e2e -am verify

# Browser suite (downloads a Chromium on first run):
mvn -Pui-e2e -pl modules/workflow-ui-e2e -am verify
```

### Trying the UI by hand

Nothing above opens a browser, and some things are only visible there — the graph on a live
process, the Errors tab, pause/resume, retry from failure. `testbench/workflow-embedded` is the
app for that: the engine, its UI and a couple of workflows, in one command and with nothing to
install.

```bash
mvn -f testbench/workflow-embedded spring-boot:run
# http://localhost:8095 → Workflow → Processes → View
```

Cron keeps a process in flight so there is always something to look at: `slow-saga` reserves a
room, waits a minute in a TIMER and then fails its charge, so the detail shows a live graph, a
compensation drawn amber, an Errors tab with a reason in it and a process that ends
`Compensated (100%)`. Turn `workflow.cron-enabled` off to keep the list still.

It runs `embedded` + **`jpa`** (H2, in memory) rather than `memory` persistence, and has to: the
UI reads the JPA entity repositories directly — the home dashboard's counts, the process detail's
steps, messages, errors and resources — so with `workflow.persistence=memory` the context does not
start at all (`NoClassDefFoundError: JpaRepository`).

## How to run — details

The e2e suite configures fast polling (`workflow.outbox-poll-interval-ms`,
`workflow.timeout-scan-interval-ms`, `workflow.cron-scan-interval-ms`) so the whole suite
stays in the tens of seconds. Cron starts are disabled suite-wide
(`workflow.cron-enabled=false`) and re-enabled only in `CronStartE2eTest`'s own context,
so the every-second `cron.json` definition does not spawn processes in unrelated tests.
