# Plan: configurable execution serialization (named per-key locks)

> Status: **DRAFT — awaiting review**. Execution is phased; each phase compiles and
> tests green on its own so this can be paused and resumed. Check items off as they land.

## 1. Goal

Let a workflow definition **serialize executions by a key** so that processes (or a
critical section within a process) that target the same entity — e.g. the same
`bookingId` — run one at a time, and the ones that arrive while the key is busy wait and
are admitted **in arrival order (FIFO)**.

Two levels, **both** in scope (confirmed):

1. **Step-level critical section** — explicit `LOCK` / `UNLOCK` steps carrying a key
   expression. Only the tramo between them is serialized; the lock is *not* held during
   long worker round-trips outside the section.
2. **Process-level lock** — a definition-level key expression (`${bookingId}`). The whole
   process instance is serialized against other instances sharing that key.

Admission order: **FIFO by arrival** (decided). Ordering-by-value is explicitly out of scope
for v1.

## 2. What already exists (and what does not)

- **`limitConcurrentExecutions` / `maxConcurrentExecutions` / `enqueueOnLimit`** are declared
  on `WorkflowDefinition` (`WorkflowDefinition.java:37-42`, getter at `:524`), persisted
  (`WorkflowDefinitionEntity.java:30-34`, `V1__baseline.sql:27-29`), parsed
  (`WorkflowDefinitionVersioningService.java:87-89`) and shown in the UI
  (`WorkflowDefinitionDetailView.java:154-156`) — **but the admission/enqueue enforcement is
  not implemented**. It is a skeleton.
  → Our per-key lock is the *generalization* of it (per-key mutex ≈ `maxConcurrent=1`
  partitioned by key + `enqueueOnLimit=FIFO`). **Design the lock admission machinery so it
  can later back `limitConcurrentExecutions` too.** We do **not** conflate the two in v1
  (see Open decisions).
- **Per-process mutual exclusion already works**: `StepOverProcessUseCase` runs the whole
  step-over inside `ProcessLockService.runExclusively()` (`StepOverProcessUseCase.java:46-54`),
  implemented as a row lock in `JdbcProcessLockService.java:38-95` and advisory-lock helpers
  in `PostgresDbLockDialect.java` (relay gate `111222333`, cron `222333444`, timeout
  `777888999`). Our lock acquisition runs *inside* this per-process lock, so intra-process
  state is already serialized; the new table's unique constraint handles the *cross-process,
  cross-pod* race.
- **Event-driven resume** is the established pattern: message correlation and timer/timeout
  expiry publish an event that lands via the process consumer group and drives
  `StepOverProcessUseCase.handle()`. Lock release will wake the next waiter the same way.

## 3. Design

### 3.1 The primitive

A lock is identified by `(lockName, lockKey)`:
- `lockName` — the lock domain, declared in the definition (e.g. `"booking"`). Lets one
  definition hold several independent locks and lets different definitions share one.
- `lockKey` — the evaluated key expression (e.g. the `bookingId` value).

A lock is **held by a process** (process-level) or **by a step execution** (step-level).
It is **created on the fly**: the first acquirer inserts the row; there is no pre-registration.

### 3.2 Persistence (migration `V29__process_locks.sql`)

**`process_lock`** — the held lock (one row per held key):
| column | notes |
|---|---|
| `lock_name` | part of PK |
| `lock_key` | part of PK |
| `holder_process_id` | |
| `holder_step_execution_id` | nullable — null for process-level |
| `acquired_at` | |
| `lease_deadline_at` | nullable — crash backstop for the reaper |
- **PK `(lock_name, lock_key)`** — this unique constraint *is* the mutex.

**`process_lock_waiter`** — the FIFO queue:
| column | notes |
|---|---|
| `id` | PK |
| `lock_name`, `lock_key` | |
| `process_id` | |
| `step_execution_id` | nullable |
| `enqueued_at` | FIFO ordering key |
- Index `(lock_name, lock_key, enqueued_at)`.

**`step_execution_entity`** gains `lock_name`, `lock_key` (so a `WAITING_ON_LOCK` step is
idempotent across restarts and the reaper can find it). Add index
`(lock_name, lock_key, status)`. (`StepExecutionEntity.java:24-98`.)

**Process-level config** goes on the definition. Store as columns
`process_lock_name` / `process_lock_key` on `workflow_definition_entity` (mirrors the
existing `limit_concurrent_executions` columns) — it also lives in the definition JSON, but
a column keeps admission queries cheap.

### 3.3 Acquire / enqueue / release (new `LockService`)

- **acquire(lockName, key, holder)** → `INSERT INTO process_lock … ON CONFLICT (lock_name,
  lock_key) DO NOTHING`. Row inserted ⇒ **acquired**. Conflict ⇒ insert a
  `process_lock_waiter` row ⇒ **enqueued** (return `WAITING`). Re-acquire by the same holder
  is a no-op (**reentrant/idempotent**), matched on `holder_process_id`.
- **release(lockName, key)** → delete the `process_lock` row, then
  `SELECT … FROM process_lock_waiter WHERE (lock_name,lock_key)=… ORDER BY enqueued_at
  LIMIT 1 FOR UPDATE SKIP LOCKED`; if a waiter exists, delete it, insert `process_lock` for
  it, and **publish a grant event** (§3.5) keyed by the waiter's `process_id` so its
  step-over runs on the right consumer.

All acquire/release happen **inside** the per-process lock already held by step-over, so
within one process it is naturally serial; the DB unique constraint + `SKIP LOCKED` handle
concurrency across processes and pods (same discipline as `OutboxDrain`).

### 3.4 State model

Add `WAITING_ON_LOCK` to `StepExecutionStatus` (`StepExecutionStatus.java`). Semantics:
- not terminal (`isTerminal()` stays false),
- not `isInFlightAtAWorker()` (nothing is dispatched),
- a `LOCK` step that cannot acquire parks in `WAITING_ON_LOCK`; when granted, the step
  **completes** and flow proceeds to the section.

**No new `ProcessStatus`** — the process stays `RUNNING`; a step waits. (Matches how the
engine already models `WAIT_FOR_MESSAGE`.)

For **process-level** locks: model as an implicit acquire attempted at process start
(before the first real step dispatch) and released on process terminal state. Simplest
implementation: a synthetic guard evaluated in `WorkflowOrchestrationService` /
`StepOverProcessUseCase` that, if the definition has `processLock`, must hold the lock before
any non-START step is dispatched; otherwise the process's steps stay `CREATED` and a waiter
is enqueued keyed by `processId`.

### 3.5 Domain events (shared module)

Add to `DomainEvent` `@JsonSubTypes` (`modules/shared/.../ddd/DomainEvent.java:12-35`), all
with `partitionKey() = processId`: `LockAcquired`, `LockWaitQueued`, `LockGranted`,
`LockReleased`. `LockGranted` drives the waiter's step-over; the others are for audit and the
graph overlay.

### 3.6 Release triggers & lease (crash backstop)

Release happens on:
1. **`UNLOCK` step** executing (step-level), or
2. **process reaching a terminal status** (COMPLETED/ERROR/CANCELLED/COMPENSATED) — release
   *all* locks the process holds. This guarantees release on the normal + error paths.
3. **Lease expiry** — backstop for a pod that crashed mid-hold. Materialize
   `lease_deadline_at` on acquire; extend the existing `TimeoutScheduler` (advisory lock
   `777888999`, `TimeoutScheduler.java:29-120`) — or add a sibling `LockLeaseScheduler` — to
   find `lease_deadline_at <= now`, force-release, wake the next waiter, and mark the holder
   step `TIMEOUT`. Reuse the deadline-materialization pattern from `V8` /
   `StepExecution.computeDeadline()`.

**Pause/resume**: shift `acquired_at` / `lease_deadline_at` the same way
`ResumeProcessUseCase` shifts `startedAt` (`ResumeProcessUseCase.java:55-59`).

### 3.7 Deadlock / reentrancy

- Reentrancy: same `(lockName, lockKey)` re-acquired by the same process is a no-op.
- Deadlock: a process holding lock A that waits for B while another holds B waiting for A.
  v1 mitigation: **acquire in a canonical order** (sort by `lockName` then `lockKey`) and
  **document** that a process should not hold two locks across a worker round-trip. Lease
  expiry is the ultimate backstop.

## 4. DSL / schema

- `StepType` enum add `LOCK, UNLOCK` (`StepType.java:7`). Note the `@JsonCreator` alias
  pattern already there — no alias needed for new values.
- `Step` model add `lockName`, `lockKey` fields with backward-compatible constructor
  overloads (`Step.java:30-183`, follow the existing overload evolution).
- `WorkflowDefinition` add `processLock` (`{name, key}`) (`WorkflowDefinition.java:26-136`)
  + entity columns (§3.2) + parsing (`WorkflowDefinitionVersioningService.java:87`,
  `ImportWorkflowDefinitionsFromDirectoryUseCase.java:155,228`).
- **Canonical JSON schema** (hand-written): add `LOCK`/`UNLOCK` to the step-type enum
  (`workflow-definition-schema.json:224-238`) and a `processLock` property near
  `limitConcurrentExecutions` (`:49-66`). This is the single source of truth for the plugins.

## 5. Plugins (so the DSL is authorable)

Schemas are hand-written and **synced** into both plugins — no programmatic completion to
touch (it is JSON-schema-driven):
- VSCode: `plugins/vscode-eventconductor/scripts/sync-assets.js:19-20` copies the canonical
  schema; run `npm run compile` (`package.json:133`). Bump `package.json` version.
- IntelliJ: `plugins/intellij-eventconductor/build.gradle.kts:81-94` `syncSchema` task runs
  before `processResources`. Bump the `build.gradle.kts` version.
- Both ship self-contained schema copies, so a rebuild + republish is required (mirror commit
  #391 "Bump IDE plugin versions for refreshed .ec/.ecform schema").

## 6. Graph visualization

Frontend web component
`modules/workflow-engine/frontend/src/eventconductor-workflow-graph.ts` (compiled to
`.../META-INF/resources/eventconductor/workflow-graph.js`, embedded in engine + plugins):
- Add `LOCK`/`UNLOCK` to the `StepType` union (`:50-52`), `STEP_TYPES` (`:197-200`),
  `NODE_STYLE` (`:209-229`), and a padlock `SYMBOLS` entry (`:280-290`).
- **Lock badge** on nodes that define a lock: extend `StepOverlay` with `lockKey?`
  (`:142-154`), render a badge in `renderNode()` (`:3042+`, alongside the existing state
  badges `:3103-3177`); populate it in `SimpleProcessViewModel.overlayEntry()`
  (`SimpleProcessViewModel.java:372-403`).
- **"Waiting on lock"** state: add `WAITING_ON_LOCK` to `StepState` (`:140`), a
  `.ov-waiting-on-lock` CSS class, and map it in `overlayState()`
  (`SimpleProcessViewModel.java:337-350`).
- **Process-level lock** indicator: pass `processLock` in the graph payload and show a header
  badge in the definition view.
- Rebuild the frontend bundle (it is embedded in engine resources **and** synced to plugins).

## 7. Tests

- **Unit** (`modules/workflow-engine/src/test`): `LockService` acquire/enqueue/release FIFO;
  `StepExecution`/orchestration lock-guard path; mirror
  `StepOverProcessUseCaseTest` (`:31-150`, note its `allowLock()` mock at `:58`).
- **e2e** (`modules/workflow-e2e`): new `LockSerializationE2eTest` — two processes contending
  one key run serialized; waiter admitted FIFO on release; process-level lock; UNLOCK release;
  lease expiry; pause/resume with a held lock. Mirror `SequentialFlowE2eTest`,
  `TimeoutE2eTest`.
- **dist-e2e** (`modules/workflow-dist-e2e`): two pods contend one key → only one acquires;
  mirror `Dist09ConcurrentOutboxClaimTest`.

## 8. Phases (each is a stopping point)

- [ ] **P0 — Plan & decisions.** This doc reviewed; open decisions (§9) resolved.
- [x] **P1 — Persistence + LockService.** `V29` migration, two tables + step columns, JDBC
      `LockService` (acquire/enqueue/release FIFO, reentrant), unit tests. No engine wiring
      yet. *Compiles & tests green.* — DONE: `LockService` port +
      `InMemoryLockService` (memory) + `JdbcLockService` (jpa, `SELECT … FOR UPDATE` per key,
      insert-and-retry on fresh key, FIFO waiter promotion) + `ProcessLockEntity` /
      `ProcessLockWaiterEntity` + `V29__process_locks.sql` + `step_execution_entity` lock
      columns. Tests: `InMemoryLockServiceTest` (11) green; `LockServiceJpaE2eTest` (2, JDBC on
      H2) green.
      - **Known limitation to harden in P4:** the waiter query orders by `enqueued_at, id`; the
        `id` (UUID) tie-break is not insertion order, so two waiters enqueued in the *same*
        timestamp tick could be admitted out of arrival order. `LocalDateTime.now()` micro
        resolution makes this vanishingly unlikely across separate acquire transactions, but a
        monotonic sequence column would make FIFO exact under bursts.
- [x] **P2 — DSL model + schema.** `StepType` LOCK/UNLOCK, `Step` fields, `WorkflowDefinition`
      `processLock` + entity columns + parsing, canonical JSON schema. Round-trip
      (parse→canonical→persist) tests. — DONE: `StepType.LOCK/UNLOCK`; `Step.lockName/lockKey`
      (+ compat constructor overloads); `ProcessLock {name,key}` record + `WorkflowDefinition.processLock`
      (+ `withProcessLock`, 17-arg compat ctor, all `with*` copies preserve it); persisted via
      `process_lock_json` column (entity + `V29` + DB repo map/save) and preserved through the
      directory import; `processLock` added to the version content hash (step lock fields ride
      inside the serialized steps); schema `LOCK/UNLOCK` + `lockName/lockKey` + `processLock`;
      domain invariant rejects a lock step with no `lockKey`. Tests: `LockDslRoundTripTest` (4)
      green; no regressions (the 2 red import-prune tests fail identically on clean HEAD — a
      macOS `/tmp` symlink `toRealPath()` issue, pre-existing).
- [x] **P3 — Engine wiring.** `WAITING_ON_LOCK` status; acquire in step start / orchestration
      guard; release on UNLOCK + process terminal; `LockGranted` → step-over of the waiter;
      process-level guard. Unit + e2e (`LockSerializationE2eTest`). — DONE:
      `StepExecutionStatus.WAITING_ON_LOCK` (counted as active in the orchestrator so a process
      neither completes nor errors around a parked waiter; cancelled at END); `StepExecution.start()`
      treats LOCK/UNLOCK as internal (no worker) and `markWaitingOnLock()` parks without a wire
      event (WAITING_ON_LOCK is not in `TaskStatus`); `LockKeyResolver` (JEXL, mirrors
      `MessageCorrelation`); `StepOverProcessUseCase` resolves lock steps in-transaction
      (acquire→COMPLETE/park, release→COMPLETE) and wakes waiters — step-level via
      `UpdateStepExecutionUseCase`, process-level via a self step-over; process-level gate
      (`holdsProcessLockOrParks`) reads `processLock` from the process's definition snapshot and
      parks the whole instance until admitted; `releaseAll` on process terminal frees leaked locks.
      `processLock` preserved through BOTH classpath loaders (importer + read-through repo) and the
      `SimpleProcessViewModel`/CRUD-adapter status switches now cover the new state. Tests:
      `LockSerializationE2eTest` (2, step-level FIFO), `ProcessLockSerializationE2eTest` (1,
      whole-instance), `LockServiceJpaE2eTest` (2) — all green. Full suites: engine 887 (only the 2
      pre-existing macOS import-prune failures), all e2e green — no regressions.
      - **Deferred:** releaseAll also on CANCELLED/COMPENSATED terminal paths (handled in other use
        cases) — the P4 lease is the backstop until then.
- [x] **P4 — Lease reaper + crash backstop.** `lease_deadline_at`, TimeoutScheduler
      extension, force-release + wake, dist-e2e multi-pod contention test. — DONE:
      `workflow.lock.lease-ms` (default 15 min) set on acquire and on every reassignment in both
      `JdbcLockService` and `InMemoryLockService`; `LockService.expireLeases(now)` force-releases
      expired holds and admits the next waiter (JDBC re-locks and re-checks the deadline to avoid
      evicting a renewed hold); `ExpireLockLeasesUseCase` wakes the grants (step-level via
      `UpdateStepExecutionUseCase`, process-level via a step-over); `LockLeaseReaper` scheduler
      (JPA-only, advisory lock `888999111`, `workflow.lock.lease-scan-interval-ms` default 60s).
      Tests: 3 lease cases in `InMemoryLockServiceTest` (14 total, deterministic via a future `now`);
      JPA context boots with the reaper. **Deferred:** a timing-based JDBC lease e2e and a 2-pod
      dist-e2e contention test — the FOR-UPDATE coordination is the mechanism (P1) and is exercised
      on H2; a dedicated multi-pod test is follow-up.
- [~] **P5 — Domain events.** `LockAcquired/WaitQueued/Granted/Released` for audit + overlay. —
      RESCOPED: the waking is done directly (`UpdateStepExecutionUseCase` for step-level, a self
      step-over for process-level), so `LockGranted`-style wire events are not needed for function.
      Lifecycle is already observable: `start()` logs the LOCK/UNLOCK step and its key; the
      `WAITING_ON_LOCK` status + graph styling show the wait; the wake logs "Lock '…' acquired". A
      dedicated `DomainEvent` audit stream is left as optional future work rather than half-built.
- [x] **P6 — Plugins.** Schema sync, version bump, rebuild both plugins. — DONE: the updated
      canonical schema (LOCK/UNLOCK + `lockName`/`lockKey` + `processLock`) copied into both plugin
      schema dirs (`vscode-eventconductor/schema/ec.schema.json`,
      `intellij-eventconductor/src/main/resources/schema/ec.schema.json`); versions bumped
      (VSCode 0.1.17→0.1.18, IntelliJ 0.1.18→0.1.19). Completion/validation is JSON-schema-driven, so
      no code change; a full `npm run compile` / gradle publish is the release step.
- [x] **P7 — Graph visualization.** Frontend node styles + lock badge + waiting-on-lock state
      + process-level indicator; rebuild bundle; ViewModel overlay fields. — DONE:
      `eventconductor-workflow-graph.ts` gains `LOCK`/`UNLOCK` in the `StepType` union + `STEP_TYPES`
      + `NODE_STYLE` (slate nodes) + padlock `lock`/`unlock` SYMBOLS; `WAITING_ON_LOCK` added to
      `StepState` with a distinct slow slate march (`.ov-waiting_on_lock`, incl. reduced-motion);
      bundle rebuilt (`vite build`) into the embedded `workflow-graph.js`. Backend: overlay token
      `WAITING_ON_LOCK`, reason "Waiting for a lock", and all `StepExecutionStatus` switches updated.
      **Deferred:** a process-level padlock badge on the diagram header (the LOCK/UNLOCK glyphs and
      the waiting march already cover "lock on a step" and "process waiting on a lock"); and
      re-syncing the rebuilt graph bundle into the two plugins (a plugin-release asset step).
- [~] **P8 — Docs + demo.** CHANGELOG, doc site page; add a `.ec` definition in
      `ec-definitions` using `processLock: ${bookingId}` (or a LOCK/UNLOCK section around
      `confirm-booking`); wire it into the ec-demo1 booking flow; verify end-to-end. Then bump
      the engine version and ship via the usual release chain. — DONE: CHANGELOG `[Unreleased]`
      entry; reference definitions live in the test resources (`lock-serialization.json`,
      `process-lock-serialization.json`). **Remaining ship steps (cross-repo / deploy, do manually):**
      1) add a `processLock: bookingId` (or a LOCK/UNLOCK section around `confirm-booking`)
      definition to the `ec-definitions` repo; 2) release the engine to Maven Central and bump its
      version in the `ec-demo1` deploy; 3) apply and verify against the live booking flow. These
      touch Maven Central and the cluster, so they are left to a human release rather than done here.

## 9. Open decisions — RESOLVED (2026-09-19)

1. **Unify with `limitConcurrentExecutions`?** → **No for v1.** Build the lock machinery
   standalone; leave a clean seam so a later change can express `limitConcurrentExecutions`
   as a lock with N permits.
2. **Process-level lock shape in the DSL:** → **Object `{ name, key }`**, for symmetry with
   step-level. `name` optional, defaults to the definition id.
3. **Multiple permits (semaphore, N>1)** or strictly mutex (N=1)? → **Mutex only (N=1)** in
   v1; the table generalizes to a counter later.
4. **Lease default:** → **Dedicated property `workflow.lock.lease-ms`**, generous default
   (15 min, aligned with `defaultStepTimeoutMs`), configurable.

## 10. Key file anchors (from the codebase map)

Engine: `StepType.java:7` · `StepExecutionStatus.java` · `Step.java:30-183` ·
`WorkflowDefinition.java:37-42,524` · `StepExecution.java:246-392` ·
`WorkflowOrchestrationService.java:80-92,117-128,448-450` ·
`StepOverProcessUseCase.java:46-54` · `StartStepExecutionUseCase.java:42-90` ·
`ResumeProcessUseCase.java:55-59` · `JdbcProcessLockService.java:38-95` ·
`PostgresDbLockDialect.java` · `OutboxDrain.java` (SKIP LOCKED pattern) ·
`TimeoutScheduler.java:29-120` · `StepExecutionEntity.java:24-98` ·
`WorkflowDefinitionEntity.java:30-34` · migrations dir (latest `V28`, next `V29`).
Shared: `DomainEvent.java:12-35` · `TaskExecutionRequested.java`.
Schema/plugins: `workflow-definition-schema.json:224-238,49-66` ·
`vscode-eventconductor/scripts/sync-assets.js:19-20` ·
`intellij-eventconductor/build.gradle.kts:81-94`.
Graph: `eventconductor-workflow-graph.ts:50-52,197-200,209-229,280-290,3042+` ·
`SimpleProcessViewModel.java:337-350,372-403`.
