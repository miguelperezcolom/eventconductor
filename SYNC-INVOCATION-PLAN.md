# Plan: synchronous invocation of durable processes (REPLY step)

> Status: **DECISIONS RESOLVED (2026-09-24) — ready for P1**. No production code written yet.
> Execution is phased, one PR per phase; each phase compiles and tests green on its own so this
> can be paused and resumed. Check items off as they land.

## 1. Goal

Let a client **start a durable process over HTTP, wait up to a deadline, and receive a response
the process itself emits from its state** — typically a saga with compensation, which today can
only be started asynchronously.

- The process stays **100% durable**: every transition is persisted exactly as today.
- A new **`REPLY` step** emits the response (from variables or a JEXL expression) at any point of
  the graph. At the end it means "respond with the result"; earlier, the client gets its answer
  while the process carries on durably.
- A deadline that expires returns **202 + instance id + a result URL**; the process keeps going and
  the reply is served later from that URL.
- Retries with the same **idempotency key** land on the same instance.
- A **fast path** runs consecutive engine-side steps inline on the pod that received the request,
  persisting each transition but skipping the relay/broker hop between them.

### Non-goals

- **Ephemeral synchronous composition** (definitions executed outside the engine, without
  persistence) is out of scope. **No prior work on it exists in this repository**: searched all
  branches (`main` + 4 dependabot), stashes (none), commit messages and content (`-S`/`-G` for
  ephemeral/sync/reply/invoke/composition), `.dev/`, `doc/`, `CHANGELOG.md`, and the sibling repos
  `eventconductor-spans`, `mateu-workflow`, `workflows`. The only "inline" execution that exists is
  the `workflow.persistence=memory` mode (§2.4), which is a whole-engine persistence choice, not a
  per-request executor — nothing reusable for that effort beyond what this plan also reuses.
- No change to definition versioning (§3.10).
- No callback delivery in v1 (§3.4.5, decision D6).

## 2. What already exists — and where the brief clashes with the engine

Findings first, because several change the design the brief proposes.

### 2.1 There is no HTTP API to start or read a process

- The only engine REST controller that touches processes is
  `MessageRestController` (`infra/in/rest/MessageRestController.java:51`, `POST /workflow/api/messages`,
  fire-and-forget `202`). Others are git-import webhooks (`GitImportWebhookController`) and
  `TaskProjectController`. No OpenAPI spec exists.
- `StartProcessUseCase` is an **empty stub** (`application/usecases/process/start/StartProcessUseCase.java:26-28`).
- Processes start via Kafka `ProcessCreationRequested` (`ProcessCreationRequestedEventHandler.java:27-35`,
  which **mints a random processId**, so dedupe relies on `businessKey` only), cron
  (`CronStarts.java:55` → `IngressRouter.route`), the UI (`Processes.java:124`), and PROCESS steps
  (`StepExecution.java:369`). Reads are MCP-only (`WorkflowMcpTools.java:99-147`).
- `CreateProcessUseCase.handle` (`application/usecases/process/create/CreateProcessUseCase.java:75-161`)
  dedupes check-then-act on processId/businessKey (`:76-91`, backed by `business_key UNIQUE`,
  `V1__baseline.sql:48`), enforces depth (`:96-109`), disabled definitions (`:126`) and flow
  authorization (`:135,187-210`), persists steps CREATED then the process PENDING — **with no
  `@Transactional`**: in embedded+jpa each save commits on its own.

⇒ The sync API is a **new public surface**, not an extension of an existing one. We also need
the first real "start a process over HTTP" and "get a process by id" endpoints; §3.4 defines
them narrowly.

### 2.2 There is no "result" concept; variables are strings

- `Variable(String name, String value)` (`domain/aggregates/Variable.java:3`), stored as a JSON list
  in `process_entity.variables` (`ProcessDBRepository.java:54,85`). No result/output column.
- `WorkflowOrchestrationService.completeProcess` (`domain/services/WorkflowOrchestrationService.java:537-546`)
  only sets status/percentage/timestamps.
- The only value flow-back is child → parent via `outputVariables`
  (`NotifyParentStepService.java:53-62`) — a precedent for "a named subset of variables".

⇒ The reply payload is a **new, explicitly persisted value on the process** (§3.3), not derived
from variables at read time (they keep changing after an early REPLY).

### 2.3 There is no COMPENSATING status

`ProcessStatus` is `PENDING, RUNNING, PAUSED, COMPLETED, CANCELLED, ERROR, COMPENSATED,
COMPENSATION_FAILED` (`domain/aggregates/ProcessStatus.java`). **A process stays `ERROR` while it
rolls back**; `COMPENSATED` / `COMPENSATION_FAILED` are the sticky terminals
(`StepExecutionStatusUpdatedEventHandler.java:163-199`). Compensation is decided **outside the
step-over**, in `StepExecutionStatusUpdatedEventHandler.advanceCompensation` (`:119-143`) via
`CompensationService.decide` (`RUN/WAITING/DONE/FAILED/NONE`, `CompensationService.java:33-104`), and
in embedded+jpa those saves are auto-committed before the locked step-over (`:99-108`).

⇒ "Reply as soon as compensation is decided" hooks the **ERROR transition** (orchestrator,
`WorkflowOrchestrationService.java:64-75`) and asks `CompensationService` whether anything will be
undone; "reply after compensation" hooks `markCompensated` / `markCompensationFailed`, plus the
`NONE` case where ERROR is final (§3.5). No new process status is needed.

### 2.4 The 500 ms poll is not what a transition costs — but the hops are real

The brief says every transition waits `workflow.outbox-poll-interval-ms` (500 ms). That is only
true for **rows written by another pod**:

- `OutboxSignal` (`infra/out/async/OutboxSignal.java`) wakes the local relay **after commit** of
  every outbox write; the poll is the fallback for rows other pods wrote. Both relays wait on it
  (`OutboxRelay.java:118`, `EmbeddedOutboxRelay.java:111`).
- Measured engine cost per transition (`doc/.../guides/performance.md:45-61`): **p50 7.7 ms, p99
  14.1 ms** paced at 200 transitions/s, two pods, PG+Kafka in containers. The same page notes
  (`:130-145`) that across pods "the handoff is most of the traffic" and **does** pay the poll,
  because the wake signal is per-pod.

What the fast path actually removes, then, is:

1. **A relay crossing per completed step, including engine-internal ones.** START/FORK/JOIN/CHOICE
   complete inside `start()` (`StepExecution.java:255-264`) but their `StepExecutionStatusChanged`
   goes to the outbox and back before successors start; SEND_MESSAGE and LOCK/UNLOCK too
   (`StepExecution.java:305-339`, `StepOverProcessUseCase.java:99-103,173-206`). Only END completes
   in the same pass (`WorkflowOrchestrationService.java:408-446`). An ACTION crosses the relay
   twice (dispatch + status change).
2. **In kafka mode, a broker round trip + consumer poll + a separate transaction** per crossing
   (`OutboxDrain` → topic `outbox` → `OrchestratorKafkaConsumerConfig.consumeOutbox:91-94`).
3. **The cross-pod poll** whenever the partition owner is not the writer.

So a 6-step saga answered through today's path is tens of ms p50 on one machine, with a tail set by
cross-pod handoffs (up to the poll interval each). The fast path is still worth it, but the
benchmark in P4 must measure it rather than assume "500 ms per step".

### 2.5 Embedded workers do not exist in kafka mode

`WorkerEmbeddedAutoConfiguration` is `@ConditionalOnProperty(workflow.mode=embedded, matchIfMissing)`
(`modules/worker-embedded/.../WorkerEmbeddedAutoConfiguration.java:32-33`) and
`KafkaDownstreamEventPublisher` is kafka-only (`KafkaDownstreamEventPublisher.java:22`). In kafka
mode **every ACTION, RULE (`evaluate-rule`) and USER_TASK (`complete-form`) goes to a Kafka worker**
(`StartStepExecutionUseCase.java:73-90`).

⇒ "Run embedded steps inline on the receiving pod" is only meaningful in **embedded+jpa** today.
In kafka mode the fast path can inline only **engine-internal** steps (START, FORK, JOIN, CHOICE,
LOCK/UNLOCK, SEND_MESSAGE, REPLY, END) unless we allow **hybrid dispatch** — local handlers in a
kafka-mode pod. Decision D3: **not in v1** — engine-internal steps only; the plan builds the fast path so it
can take hybrid dispatch later without redesign.

### 2.6 Single writer per process is enforced differently per mode

`ProcessLockService.runExclusively` (`application/out/ProcessLockService.java`):
- `JdbcProcessLockService` (embedded+jpa): `SELECT … FOR UPDATE` on the process row
  (`JdbcProcessLockService.java:52-94`).
- `PartitionOwnedProcessLockService` (kafka): **no lock**, only a transaction; single writer comes
  from Kafka partition ownership, with `@Version` as the backstop on rebalance
  (`PartitionOwnedProcessLockService.java:12-24,137-150`, pinned by DIST-11).
- `InMemoryProcessLockService` (memory): per-process `ReentrantLock`.

⇒ A pod running steps inline in kafka mode is a **second writer** next to the partition owner.
The design must make that safe (§3.6.3), not assume ownership.

### 2.7 The outbox cannot be queried per process

`OutboxMessageEntity` has `id, timestamp, status, messageType, payload, traceParent`
(`infra/out/persistence/OutboxMessageEntity.java:37-62`), indexed `(status, timestamp)`. The partition
key is only inside the payload (`OutboxDrain.java:221`). The kafka relay claims with
`FOR UPDATE SKIP LOCKED` in one transaction (`OutboxDrain.java:157,317`,
`PostgresDbLockDialect.java:28-30`); the embedded relay is leader-elected (advisory lock
`444555666`), **not transactional**, dispatches then marks Sent (`EmbeddedOutboxRelay.java:62-98`).

⇒ The fast path needs a `process_id` column and an **atomic per-row claim** both relays respect.

### 2.8 No wake-up mechanism across pods exists

No `LISTEN`/`NOTIFY`, no reply topic, no correlation-id header, no pod-to-pod HTTP, no in-memory
waiter registry. The only wake-up is the pod-local `OutboxSignal` semaphore. Supported databases:
PostgreSQL, MariaDB, Oracle, H2 (`*DbLockDialect.java` in `infra/out/persistence/`), so anything
Postgres-specific needs a portable fallback.

### 2.9 Sharding: shards never talk to each other

A shard is a full engine with its own DB and topics (`doc/.../guides/sharding.md:17-18,44-60`); the
only links are the shared `messages` / `process-index` topics and the fleet DB (placement,
subscriptions, process index). Placement is claimed once per business key, round-robin for new keys
(`IngressRouter.java:28-31,109-149`, `JdbcProcessPlacementStore.java:18-19`). A pod knows its shard
only via `workflow.sharding.shard-id`. Everything is off by default.

### 2.10 Validation has no forward reachability analysis

`WorkflowDefinition.checkInvariants()` (`domain/aggregates/WorkflowDefinition.java:371-471`) checks
roots, references and cycles (DFS `:457-470,551-564`); `topologyWarnings()` (`:493-548`) warns on
fan-in/out shape. **Nothing reasons about which steps can co-occur in one instance.** The Maven
plugin re-implements a subset on raw JSON (`ValidateMojo`, `addWorkflowSemantics:144-242`) and has
**already drifted** (no single-START, TIMER, message, or `lockKey` checks). Edges are only incoming
links on the target step (`Step.resolvedPreconditions()`, `Step.java:278-309`); CHOICE is exclusive
and latches (`WorkflowOrchestrationService.java:212-243`); cycles are rejected, so the graph is a
DAG; DYNAMIC steps inject new steps at runtime (`InjectStepsUseCase.java:100`).

### 2.11 Tracing is anchored per process

`ProcessTrace.anchorFor(processId)` derives the trace from the process id so every pod computes the
same parent (`StepOverProcessUseCase.java:69-74`, `ProcessTrace.java:33-54`); the outbox carries
`traceParent` across the async gap (`OutboxMessageEntity.java:50-62`, `OutboxDrain.java:300`). An
HTTP request arrives with **the client's** trace, which is a different trace by construction.

## 3. Design

### 3.1 The REPLY step

**DSL.** New `StepType.REPLY` (`domain/aggregates/StepType.java:7-18`). Two new `Step` fields,
appended to the canonical record with compat constructor overloads (the pattern at
`Step.java:221-253`), each `@Hidden("state['type'] != 'REPLY'")`:

| field | meaning |
|---|---|
| `replyVariables: [String]` | reply = JSON object of these process variables (mirrors `outputVariables`) |
| `replyExpression: String` | reply = value of a JEXL expression over the standard context |

Exactly one of the two. Neither → reply `{}` (an ack). The JEXL context is the one every caller
already builds — `process`, each variable, `businessKey` last (`LockKeyResolver.resolveExpression`,
`LockKeyResolver.java:32-51`) — evaluated with the engine's `JEXLEvaluator`
(`application/services/JEXLEvaluator.java`, RESTRICTED). The result is serialized to JSON (maps
and lists pass through; since variables are strings, `replyVariables` is the common case and
`replyExpression` the escape hatch, e.g. `{'bookingId': bookingId, 'status': 'CONFIRMED'}`).

**Execution — engine-internal, resolved in the step-over transaction.** Follow the LOCK/UNLOCK
precedent exactly: `start()` moves REPLY to PENDING (no worker), and a new
`resolveReplySteps(process, stepsToSave)` in `StepOverProcessUseCase` (next to
`resolveLockSteps`, `:103,173-206`) evaluates the payload, records it on the process
(`process.recordReply(stepId, payload, REPLIED)`), and completes the step — **in the same transaction
as the state it reads**. The process is then saved (it must be added to the save condition at
`StepOverProcessUseCase.java:105-107`). Evaluation failure → step `ERROR` → normal failure path.

*Discarded:* resolving in `StepExecution.start()` like SEND_MESSAGE. `start()` returns only the step
and cannot mutate the process aggregate; the step-over is where the process and the step are
both in hand under the process lock.

**Once only, enforced by the aggregate.** `Process.recordReply` is a no-op + warning log if the
process has already replied (defense in depth for what validation cannot see: DYNAMIC-injected
steps, operator retry after a failure reply, §3.5.3). DYNAMIC injection of a REPLY step is
rejected in `InjectStepsUseCase`.

**A REPLY in a process started asynchronously** still records its reply (visible in the UI and the
GET endpoint). The same definition therefore runs identically sync or async; nothing branches on
"was I invoked synchronously" inside the engine.

**REPLY is not allowed** as a compensation target (`compensationStepId`) — a compensation runs on
the rollback path, whose reply is governed by §3.5.

**Definition-level config**, new `WorkflowDefinition.syncInvocation` record (field appended at
`WorkflowDefinition.java:114` + `with*`/compat ctors as `processLock` did):

```json
"syncInvocation": {
  "enabled": true,
  "onFailure": "REPLY_IMMEDIATELY",      // | REPLY_AFTER_COMPENSATION
  "onLockBusy": "WAIT",                  // | FAIL   (process-level lock only, §3.8)
  "defaultDeadlineMs": 5000
}
```

`enabled` gates the sync endpoint (a definition not marked invocable returns 404/409 there — the
async starts are unaffected). It must be added to the explicit definition-level list in
`WorkflowDefinitionVersioningService.contentHash` (`:82-106`); step fields ride in the serialized
steps automatically. Persisted like `processLock` (a JSON column on `workflow_definition_entity`,
V31) and preserved through **both** classpath loaders — the lesson recorded in
LOCK-SERIALIZATION-PLAN P3.

### 3.2 Validation: "at most one reachable REPLY per instance"

**The rule.** Two REPLY steps A and B are compatible iff they can never both execute in one
instance. On this engine's DAG that holds only when they are separated by an **exclusive split**:
- a **CHOICE** (exclusive, latching) with A and B only reachable through *different* branches, and
  the CHOICE dominates both (every path from START to A and to B passes through it); or
- a step with **`onTimeoutStepId`**: its normal successors and its timeout target are exclusive (a
  TIMEOUT step never COMPLETES).

Everything else is **conservatively concurrent**: FORK branches, implicit fan-out (a non-FORK with
several successors is allowed with a warning, `topologyWarnings`), links with WAIT/DISCARD guards
(guards read variables, so assume they can all be true), and ancestor/descendant pairs (a path
through both). This is sound (never accepts a definition that can reply twice) and slightly
incomplete (rejects definitions where guards happen to be mutually exclusive — the author can
express that with a CHOICE).

**Algorithm (O(V·E), fine for definitions of hundreds of steps).**
1. Build the successor graph from `resolvedPreconditions()` (+ `onTimeoutStepId` edges; compensation
   edges excluded).
2. For every exclusive split S (CHOICE or timeout-routing step), label each node N with
   `branches(S, N)` = set of S's out-edges through which N is reachable; compute dominators of S
   (standard iterative dominator algorithm on the DAG).
3. For each REPLY pair (A, B): reject if A reaches B or B reaches A; accept if some exclusive S
   dominates both with `branches(S,A) ∩ branches(S,B) = ∅` and both non-empty; otherwise reject,
   naming the first co-reachable ancestor in the error ("REPLY 'r1' and 'r2' can both run: both are
   reachable from FORK 'f'").
4. If `syncInvocation.enabled`: at least one REPLY reachable from START → **error** otherwise.
   Additionally **warn** for every END (or implicit-completion sink) that has a path from START
   avoiding all REPLY steps: such a run completes with outcome `COMPLETED_WITHOUT_REPLY` (§3.5).
   Decision D2: a warning, not an error.

**Where it lives — decision.** Today the engine and the Maven plugin each carry their own
semantics code and have already drifted (§2.10). This rule is too subtle to write twice.
- **Chosen:** a dependency-free analyzer (`ReplyPathAnalyzer`, plus a tiny `DefinitionGraph` built
  from Jackson `JsonNode`) in a new small module `modules/definition-analysis` (Jackson + JDK only),
  depended on by `workflow-engine` (called from `checkInvariants()`, so import, git import,
  classpath load and `WorkflowDefinitionDBRepository.save` all get it via `WorkflowDefinitionValidator.validate`)
  and by `workflow-maven-plugin` (called from `addWorkflowSemantics`).
- *Discarded:* duplicate it in the plugin (repeats the drift); make the plugin depend on
  `workflow-engine` (drags Spring Boot into a Maven plugin classpath). Moving the *existing*
  invariants into the new module is desirable follow-up, not part of this work (decision D9).

**JSON schema** (`modules/workflow-engine/src/main/resources/workflow-definition-schema.json`):
`REPLY` in the step type enum (`:246-265`); `replyVariables` / `replyExpression` properties near
`outputVariables` (`:402`); an `allOf` if/then block `REPLY → not both` next to the others
(`:539-670`); top-level `syncInvocation` next to `processLock` (`:74`). The plugin validates
`replyExpression` as JEXL like it already does for `correlationExpression`.

**IDE plugins.** Schema-driven, no code: sync + version bump + rebuild, as LOCK P6
(`plugins/vscode-eventconductor/scripts/sync-assets.js:19-32`,
`plugins/intellij-eventconductor/build.gradle.kts:93-113`). Note in passing: the untracked
`plugins/*/schema/` dirs in the working tree are **stale** copies (no LOCK/`task`/`processLock`),
untracked only because `rule.schema.json` is missing from both plugins' `.gitignore`; fix the
`.gitignore` in the same PR.

**Graph component** (`modules/workflow-engine/frontend/src/eventconductor-workflow-graph.ts`):
`REPLY` in the `StepType` union (`:50-53`), `STEP_TYPES` (`:198-202`), `NODE_STYLE` (`:214-235`,
a `Record`, so it will not compile without it), a glyph in `SYMBOLS` (`:286`), editor fields
(`:3323+`); rebuild the embedded bundle.

### 3.3 Persistence (migration `V31__sync_invocation.sql`)

**On `process_entity`** — the reply belongs to the process, saved atomically with whatever
transition produces it, from whichever use case:

| column | notes |
|---|---|
| `reply_json` | TEXT, nullable — the payload |
| `reply_outcome` | `REPLIED`, `FAILED`, `COMPENSATED`, `COMPENSATION_FAILED`, `CANCELLED`, `COMPLETED_WITHOUT_REPLY` |
| `reply_compensation` | `NONE`, `IN_PROGRESS`, `DONE`, `FAILED` (§3.5) |
| `reply_step_id`, `replied_at` | |

*Discarded:* storing the reply only in `sync_invocation`. The reply is produced from several
places (step-over, the compensation handler, cancellation) and must commit with the transition; the
process row is the one thing all of them already save, with `@Version`.

**`sync_invocation`** — the request side:

| column | notes |
|---|---|
| `id` | PK (UUID), returned to the client |
| `workflow_definition_id`, `idempotency_key` | **UNIQUE together** — idempotency scope is per definition |
| `request_hash` | SHA-256 of the canonical request body; same key + different body → 422 |
| `process_id` | |
| `deadline_at`, `created_at`, `expires_at` | `expires_at` drives purge (`workflow.sync.retention`) |
| `caller_trace_parent` | for span links (§3.11) |
| `on_failure` | snapshot of the definition's policy at invocation time |

**`outbox_message_entity`** gains `process_id` (from `partitionKey()`, nullable), `claimed_by`,
`claim_until`, a status value `InlineClaimed`, and index `(process_id, status, timestamp)` — also
declared on the entity, per the note at `OutboxMessageEntity.java` about `ddl-auto` schemas.

### 3.4 Invocation API

#### 3.4.1 Endpoints

New `SyncInvocationController` in `infra/in/rest/`, alongside `MessageRestController`, same auth
model (optional `X-Api-Key` — `MessageApiProperties` — plus the existing per-definition flow
authorization in `CreateProcessUseCase:187-210`, which the invocation calls):

| | |
|---|---|
| `POST /workflow/api/definitions/{definitionId}/invocations` | start + wait. Headers: `Idempotency-Key` (required), `Prefer: wait=<seconds>` (RFC 7240; default `defaultDeadlineMs`, capped by `workflow.sync.max-deadline-ms`). Body: `{ businessKey?, variables: {…} }` |
| `GET /workflow/api/invocations/{invocationId}` | result; optional `Prefer: wait=N` long-poll |
| `GET /workflow/api/definitions/{definitionId}/invocations?idempotencyKey=…` | lookup by key (the client that lost the connection may not have the id) |

Served with Spring MVC async (`DeferredResult`) so a waiting request holds no servlet thread; the
inline execution (§3.6) runs on its own bounded executor.

#### 3.4.2 Responses

| case | status | body |
|---|---|---|
| replied (`REPLIED`) | **200** | envelope, `reply` = payload |
| failure outcome — `FAILED`, `COMPENSATED`, `COMPENSATION_FAILED`, `CANCELLED` (§3.5) | **502** (D4) | envelope, `outcome`, `compensation`, error summary |
| completed without passing a REPLY | **200** (D2) | envelope, `reply: null` |
| missing `Idempotency-Key` | **400** (D10) | |
| reply over `workflow.sync.max-reply-bytes` | REPLY step fails → failure outcome (D11) | |
| deadline expired | **202**, `Location: /workflow/api/invocations/{id}`, `Retry-After` | envelope without reply |
| process-level lock busy with `onLockBusy: FAIL` | **409**, `Retry-After` | no instance created |
| same key, different body | **422** | |
| admission full (§3.9) | **429**, `Retry-After` | nothing created |
| definition not sync-invocable | **409** | |

Envelope: `{ invocationId, processId, outcome, compensation, reply, processStatus, repliedAt }`.
`processStatus` is separate from `outcome` on purpose: after an **early REPLY** the client sees
`outcome=REPLIED` and a `processStatus` that is still `RUNNING` — and later maybe `ERROR`.
An early reply is a commitment; a later failure is the process's business (compensation), not a
second answer. Documented as such.

#### 3.4.2b Streaming progress over SSE (added 2026-09-24, owner request)

The same invocation (POST) and the result endpoint (GET) also answer `Accept: text/event-stream`:
instead of one JSON body at the end, the caller receives Server-Sent Events while it waits, so a UI
can show the process moving.

| event | data |
|---|---|
| `status` | `{ processId, processStatus }` on every process status change (first event: the instance id) |
| `step` | `{ stepId, stepName, type, status, at }` on every step status change |
| `log` | `{ stepId, level, message, at }` for the process's log lines |
| `reply` | the envelope of §3.4.2, as soon as the reply is recorded |
| `timeout` | `{ invocationId, location }` when the deadline expires — the SSE counterpart of 202 |

- **Source: persisted state, not a new bus.** The stream reads the process, `step_execution` and
  the process log every `workflow.sync.stream-interval-ms` (200) and sends what changed. So it works
  across pods and shards exactly like the reply does and adds nothing to the transition's
  transaction.
- **Resumable, at-least-once.** Event ids never decrease (the moment the event describes, or the
  last id sent if that is later). A reconnect with `Last-Event-ID` is sent everything from
  `RESUME_LOOKBACK_MS` (5 s) before that moment — a row committed late can carry an earlier time
  than events already sent — and every event's data carries a stable `key`
  (`status:…`, `step:<execution>:<status>`, `log:<id>`) so the caller drops what it already has.
- **After the reply**, the stream closes by default; `?follow=true` keeps it open until the process
  reaches a terminal status or the deadline, for callers that want to watch the rest of the saga.
- Same admission budget as a waiting request (`workflow.sync.max-waiting`), same deadline cap.
- Log lines are the ones already persisted (`LogMessageRepository`); no payload variables are
  streamed beyond what the reply and the log already expose.

#### 3.4.3 Idempotency

In **one transaction**: `INSERT INTO sync_invocation … ON CONFLICT (definition, key) DO NOTHING`,
and only if the row was inserted, create the process. That requires making the creation path used
here transactional (today `CreateProcessUseCase` is not — §2.1); the invocation wraps
`CreateProcessUseCase.handle` in a `TransactionTemplate`, which its repository saves join.
- Conflict → read the existing row: body hash differs → 422; process already replied → return it
  (200/502) immediately; otherwise **attach as another waiter** on the same invocation (up to the
  new request's deadline).
- The processId is generated by the invocation, not by `ProcessCreationRequestedEventHandler`
  (which mints a random one, §2.1).
- If the client also sends a `businessKey`, the existing unique constraint still applies; a
  different idempotency key with an existing businessKey → 409.
- Rows are purged after `workflow.sync.retention` (default 24 h), reusing the outbox purge
  scheduler pattern.

*Discarded:* using the idempotency key as the `businessKey`. The business key has meaning
elsewhere (message correlation, placement, uniqueness across all starts); overloading it would make
a client's retry key a correlation key.

#### 3.4.4 Deadline expiry

The waiter completes its `DeferredResult` with 202 at `deadline_at`; nothing happens to the
process. When REPLY lands later, the reply is on the process row; `GET …/invocations/{id}` returns
it (and a long-polling GET is woken the same way as the original waiter, §3.7).

#### 3.4.5 Callback — evaluated, deferred

A `callbackUrl` needs outbound HTTP with retries, signing and SSRF protection, and turns the engine
into a webhook dispatcher. The definition can already do this durably with an ACTION or
SEND_MESSAGE after the REPLY. **Recommendation: not in v1**; if wanted later, implement as a
`SyncReplyAvailable` outbox event consumed by a dedicated delivery worker, not inline in the engine.
(Decision D6.)

### 3.5 Error contract

A failure **before** any REPLY produces a reply with a failure outcome. Policy per definition
(`syncInvocation.onFailure`), snapshotted on the invocation:

| policy | when the reply is recorded | `outcome` / `compensation` |
|---|---|---|
| `REPLY_IMMEDIATELY` (default, D5) | the step-over that moves the process to ERROR (`WorkflowOrchestrationService.java:64-75`, surfaced as `result.isProcessErrored()` in `StepOverProcessUseCase:140-148`) | `FAILED` / `IN_PROGRESS` if `CompensationService.decide` would RUN or WAIT, else `NONE` |
| `REPLY_AFTER_COMPENSATION` | `markCompensated` → `COMPENSATED`/`DONE`; `markCompensationFailed` → `COMPENSATION_FAILED`/`FAILED`; ERROR with decision `NONE` → `FAILED`/`NONE` (`StepExecutionStatusUpdatedEventHandler.java:119-199`) | as listed |

The response therefore always says which case it is (`compensation` field), as the brief requires.

Other terminal paths without a reply: **cancelled** → `CANCELLED`; **completed without passing a
REPLY** → `COMPLETED_WITHOUT_REPLY` (reply `null`, HTTP 200, D2).

#### 3.5.1 Hook points

All of them go through `Process.recordReply(...)`, which is idempotent (first reply wins), so
redelivery and the "decided in two places" nature of compensation are safe:
- `StepOverProcessUseCase.doHandle` for REPLIED / ERROR-immediate / completed-without-reply;
- `StepExecutionStatusUpdatedEventHandler.markCompensated` / `markCompensationFailed` and the `NONE`
  branch of `advanceCompensation`;
- `CancelProcessUseCase` for CANCELLED.

**Known wrinkle:** in embedded+jpa, `advanceCompensation`'s saves are outside the step-over lock
(§2.3). The reply rides on the same `processRepository.save` (optimistic `@Version`), so it is as
safe as the compensated status itself — no new race — but P3 adds an e2e that redelivers the
compensation completion to prove the reply is written once.

#### 3.5.2 Mapping the process error

The error summary is taken from the failed step's last error log (`UpdateStepExecutionUseCase`
writes it, `:213`), exposing `stepId`, `stepName` and the message. **No stack traces** in the
response.

#### 3.5.3 Operator retry after a failure reply

`RetryProcessUseCase` may revive an ERROR process that already replied `FAILED`. When it later
reaches REPLY, `recordReply` keeps the first reply (logged). The GET endpoint shows the current
`processStatus`, so the operator's fix is visible; the client's answer does not change. (D7.)

### 3.6 Fast path: inline execution with the outbox as the recovery log

#### 3.6.1 Idea

Keep writing every transition and its outbox rows exactly as today. After each commit, the pod that
holds the request **claims the process's next outbox row itself** and runs the same handler the
relay would have run (`ProcessDomainEventUseCase` → the `DomainEventHandler` list,
`ProcessDomainEventUseCase.java:15-27`), marking the row handled **in the handler's transaction**.
Repeat until there is no claimable row for the process, or the next step must wait (Kafka worker,
TIMER, WAIT_FOR_MESSAGE, USER_TASK, PROCESS, WAITING_ON_LOCK), then fall back to the normal async
path and wait for the reply notification (§3.7).

Because the row is written and committed **before** anyone handles it, a crash at any point leaves
either a `Pending` row (the relay picks it up — today's recovery, `CrashRecoveryE2eTest`) or a
committed transition. The fast path adds no new durable state and no new failure mode beyond the
claim itself.

#### 3.6.2 The claim

`InlineOutboxDriver` (new, `infra/out/async/`):

```
claim(processId):
  UPDATE outbox_message_entity
     SET status='InlineClaimed', claimed_by=:pod, claim_until=now()+:lease
   WHERE id = (SELECT id FROM outbox_message_entity
                WHERE process_id=:pid AND status='Pending'
                ORDER BY timestamp, id LIMIT 1)
     AND status='Pending'
```
Row count 0 → the relay (or another pod) got there first → **stop inlining**, it is now on the
normal path. Only rows whose `RelayDestination` is the local `outbox` destination are eligible;
`MessageReceived` to `messages` and `ProcessStatusChanged` to `process-index`
(`RelayDestination.java:334-342`) are always left to the relay. Claiming the **oldest** pending row
of the process keeps per-process order: if the oldest one is not claimable, nothing newer is
inlined.

The relays must honour it:
- `OutboxDrain` already claims `status='Pending'` with `SKIP LOCKED` → `InlineClaimed` rows are
  naturally invisible. No change beyond the new column.
- `EmbeddedOutboxRelay` must switch from "read Pending, dispatch, mark Sent" to the same **CAS**
  (`UPDATE … WHERE id=? AND status='Pending'`) before dispatching — today two dispatchers would
  both run the row (§2.7).

**Lease + heartbeat.** An embedded worker can run for a long time inside a claimed
`TaskExecutionRequested`. The driver renews `claim_until` for all rows it holds with one statement
every `lease/3`; a sweeper (reuse `TimeoutScheduler`'s advisory-locked tick,
`TimeoutScheduler.java:29-120`) returns `InlineClaimed` rows with `claim_until < now()` to
`Pending`. A dead pod stops renewing → its rows go back to the relay → **at-least-once, exactly as
today's embedded path**, where a crash mid-worker leaves the row Pending
(`EmbeddedOutboxRelay.java:79-84`).

#### 3.6.3 Single writer

The inline handler runs under the ordinary `ProcessLockService.runExclusively`. In embedded+jpa
that is the process row lock — correct as is. In kafka mode it is **no lock**
(`PartitionOwnedProcessLockService`), and the partition owner may be handling a parallel branch's
worker reply for the same process at the same moment. For inline execution in kafka mode we take
the process row lock explicitly (the `JdbcProcessLockService` query), and the partition owner is
protected by `@Version`: its transaction fails with `ConcurrentProcessAccessException` and the batch
is redelivered — the exact path the engine already uses on rebalance. Rare (only processes with
parallel branches during their inline stretch) and correct; P4 has a test that forces it.

*Discarded:* sending the request to the partition owner. Owners are per partition, not per key,
change on rebalance, and there is no pod-to-pod HTTP; see §3.7.

#### 3.6.4 What gets inlined, per mode

| mode | inlined | falls back at |
|---|---|---|
| `persistence=memory` | everything already runs inline on the caller's thread (`InMemoryStepExecutionRepository.java:35-38`) — **no work needed** | nothing to do |
| embedded + jpa | engine-internal steps **and embedded tasks** (`TaskDispatcher`) | TIMER, WAIT_FOR_MESSAGE, USER_TASK, PROCESS, WAITING_ON_LOCK, AWAITING_RETRY |
| kafka + jpa | engine-internal steps (hybrid local handlers: not in v1, D3) | any Kafka worker step, plus the above |

For embedded tasks the worker runs **outside any transaction**: the claim commits, the task runs,
the reply goes through `TransactionAwareReplySink` → `UpdateStepExecutionUseCase` in its own
transaction (`worker-api/.../TransactionAwareReplySink.java:42-52`), which writes the next outbox
row, which the driver claims. `worker-threads>0` is respected: the handoff to the pool ends the
inline stretch.

**Budget.** `workflow.sync.inline.max-steps` (default 64) per invocation, so a large fan-out cannot
monopolize the executor; past it the process simply continues on the relay.

#### 3.6.5 Rejected alternatives

- **One transaction across consecutive steps.** Fewer commits, but holds locks across embedded
  worker calls, puts worker side effects inside a transaction that can roll back, and breaks
  "every transition persisted".
- **In-memory event chaining without outbox rows** (the memory-mode approach, on JPA). A crash
  between two in-memory hops loses the event: exactly what the outbox exists to prevent.
- **Running only on the partition owner.** Requires forwarding the HTTP request (§3.7).

#### 3.6.6 Side benefit, not in scope

The driver is not sync-specific: any pod could inline the next internal step of a process it just
wrote. Worth measuring after P4, but enabling it for async starts is a separate decision.

### 3.7 Waking the waiting request across pods and shards

The reply is always a **committed row** (`process_entity.reply_*`). The question is only how the
pod holding the connection finds out quickly. Options:

| option | verdict |
|---|---|
| **A. Route the request to the pod/shard that "owns" the key** | **Rejected.** Ownership in kafka mode is per partition, moves on rebalance, and the step that replies may be completed by a worker reply landing on whichever pod owns the partition *then*. There is no pod-to-pod HTTP today, and adding it (discovery, forwarding, auth) buys nothing the options below don't. |
| **B. Postgres `LISTEN/NOTIFY`** | **Chosen as the accelerator.** `pg_notify('ec_sync_reply', processId)` issued **inside the transaction that records the reply**: Postgres delivers notifications only on commit, so it is exactly as durable as the reply — no hop, no outbox row, no broker. Each pod holds one dedicated listening connection (outside Hikari; **not through PgBouncer in transaction mode**). Payload is just the id (8000-byte limit). Cost: `NOTIFY` takes a global queue lock at commit; issued only by the reply transaction, at sync-invocation rates it is negligible — P5 measures it. |
| **C. Reply topic on Kafka with correlation** | **Rejected as primary.** The reply would take an extra outbox → relay → broker hop (the latency we are removing), every pod must consume every reply (per-pod consumer groups), and it does nothing for embedded+jpa multi-pod. |
| **D. Restrict to processes that complete inline** | **Rejected as a rule**, but it is the degenerate case the design already covers: no notification needed when the reply lands on the waiting pod. |

**Layered, like `OutboxSignal`** (`SyncReplyWaiters`, new):
1. **Local signal** — in-JVM map `processId → waiters`; `recordReply` registers an after-commit
   callback that completes local waiters. This is the common case with the fast path, and the whole
   story for `persistence=memory`.
2. **`LISTEN/NOTIFY`** (PostgreSQL dialect only) — wakes waiters for replies committed on other
   pods of the **same database**.
3. **Batched poll fallback** — one query per pod every `workflow.sync.poll-interval-ms` (default
   250) over *all* its waiting ids: `SELECT id, reply_outcome FROM process_entity WHERE id IN (…) AND
   replied_at IS NOT NULL`. Covers MariaDB/Oracle/H2, missed notifications (listener reconnect), and
   bounds the damage of a broken listener to one poll interval.

**Sharding.** Shards have separate databases, so NOTIFY and the poll only see the local shard. So:
- **Place synchronous invocations on the shard that received them.** `IngressRouter` gains a
  "local" placement for sync starts: claim `process_placement` with the local shard id (keyed by the
  business key if any, else by the idempotency key), and create the process locally rather than
  publishing `ProcessCreationRequested` to another shard's `upstream`. The load balancer already
  spreads requests across shards, so the round-robin goal of placement is preserved in aggregate.
- **A retry that lands on another shard** (same key): the `sync_invocation` row is in the first
  shard's DB, so that shard's placement claim for the key is found in the fleet DB → respond **202**
  with the result URL instead of waiting. Serving that URL from any shard needs the reply in the
  fleet read model: project `reply_outcome`/`reply_json` into the process index via the existing
  `ProcessStatusChanged` → `process-index` flow. Cross-shard retries are rare; they get
  correctness, not latency. (D8.)

**If the waiting pod dies.** The client gets a connection error. Nothing about the process depends
on the waiter: the reply is recorded by whichever pod executes the REPLY. The client retries with
the same idempotency key (or GETs by key/id) and receives the recorded reply, or waits again if it
has not happened yet. Inline claims the dead pod held return to the relay after the lease (§3.6.2).

### 3.8 Locks

- **Step-level LOCK/UNLOCK** inside a synchronously invoked process: always **WAIT** (FIFO, as
  async). A long wait becomes a 202 via the deadline. Failing a step for being busy would change
  what the definition means depending on how it was started.
- **Process-level lock** (`processLock`): `onLockBusy: WAIT` (default) or `FAIL`. With `FAIL`, the
  first step-over's `holdsProcessLockOrParks` (`StepOverProcessUseCase.java:93`) runs **inside the
  invocation's creation transaction** on the fast path; if the lock would be ENQUEUED, the whole
  transaction (process, steps, `sync_invocation` row) rolls back and the client gets **409 +
  Retry-After** — no instance, and the idempotency key is free for the retry. This needs
  `LockService.tryAcquire` (acquire-or-nothing, no waiter row) next to `acquire`
  (`JdbcLockService.java:101`).
- **Default WAIT** because the deadline already bounds the client's wait and preserves FIFO
  fairness; FAIL is for callers that would rather retry than queue. Caveat to document: with WAIT
  the instance keeps its place in the queue and **will run** even if the client gave up at 202 —
  that is the durable contract. (D1.)

### 3.9 Admission control

Two separate budgets per pod, so sync traffic cannot starve the async pipeline:
- `workflow.sync.max-waiting` (default 200): open waiting requests. Full → **429 + Retry-After**
  before anything is created.
- `workflow.sync.inline.threads` (default 8, keep ≤ ¼ of the Hikari pool): the inline executor.
  **Saturated → no rejection**: the invocation is created and simply takes the normal async path
  (graceful degradation to today's latency).
The async side (relay, consumers, `workflow.consumer.process-parallelism`,
`OrchestratorKafkaConsumerConfig.java:59-81`) is untouched. `limitConcurrentExecutions` stays
unimplemented; not conflated (as in LOCK-SERIALIZATION-PLAN §9).

### 3.10 Versioning

No change: the invocation starts the definition's active version like any other start, the process
snapshots its definition, and `syncInvocation` / REPLY are read from the snapshot. A new version that
removes REPLY does not affect in-flight invocations.

### 3.11 Observability

**Metrics** (`MicrometerWorkflowMetrics` / `WorkflowMetrics`, tags `workflowDefinitionId`, `outcome`):
- `eventconductor.sync.invocations` (counter, `outcome`: replied/failed/compensated/…/deadline/rejected/lock_busy)
- `eventconductor.sync.reply.latency` (timer, request received → reply committed; `path` = inline/async)
- `eventconductor.sync.response.latency` (timer, request → HTTP response written)
- `eventconductor.sync.deadline.expired` (counter)
- `eventconductor.sync.waiting` (gauge, per pod)
- `eventconductor.sync.inline.steps` (distribution: steps run inline per invocation) and
  `eventconductor.sync.inline.fallbacks` (counter, `reason`: worker/timer/message/claim_lost/budget/saturated)
- `eventconductor.sync.wakeups` (counter, `via`: local/notify/poll) — shows whether NOTIFY is working.
Panel in `demo/.devops/observability/dashboards/eventconductor-engine.json`.

**Tracing.** The process keeps its per-process anchored trace (§2.11); re-parenting it under the
client's trace would break "one trace per process, on every pod". Instead:
- the HTTP server span (client's trace) gets a **span link** to the process anchor and a child span
  `eventconductor.sync.invoke` covering creation + inline stretch + wait;
- `caller_trace_parent` is stored on `sync_invocation`; the REPLY step's span (in the process trace,
  on whatever pod runs it) gets a **link back** to the caller.
Both directions are navigable in Tempo/Jaeger, including across pods. Sampling caveat: the process
trace is sampled by its id hash (`ProcessTrace.java:36-54`) independently of the caller; a link to
an unsampled trace is dangling. Documented, not fixed.

**UI** (`infra/in/ui/pages/process/SimpleProcessViewModel.java`): a "Synchronous" badge next to
`status` (`:104`) when a `sync_invocation` exists; a **Reply** tab next to Variables (`:168-170`)
with outcome, compensation, replied-at, REPLY step and the payload; loaded in `load()` (`:172-214`).
Graph: REPLY node glyph (§3.2) and a "replied" marker on the step that produced it.

## 4. Tests

### Unit (`modules/workflow-engine/src/test`, `modules/definition-analysis/src/test`)
- `ReplyPathAnalyzerTest`: sequential double REPLY (reject); REPLY on each CHOICE branch (accept);
  on FORK branches (reject); after XOR JOIN of CHOICE branches (reject, both reachable on one path);
  nested CHOICE; onTimeout branch vs normal successor (accept); implicit fan-out (reject); guarded
  links (reject, documented incompleteness); sync-enabled with no reachable REPLY (error); END path
  avoiding REPLY (warning); REPLY as compensation target (error).
- `Process.recordReply` once-only; JEXL payload evaluation (variables, expression, undefined
  variable, evaluation error → ERROR).
- `StepOverProcessUseCaseTest` (mirror `:31-150`): REPLY resolved in-pass, process saved.
- Failure-policy mapping for each `CompensationService` outcome.
- `InlineOutboxDriver`: CAS claim lost to relay, oldest-row rule, destination filter, lease renew /
  expiry → Pending.
- `SyncReplyWaiters`: local wake, poll wake, deadline, multiple waiters on one invocation.
- Maven plugin: `ValidateMojo` rejects/accepts the same fixtures as the analyzer test.

### E2E (`modules/workflow-e2e`, embedded; memory **and** H2/JPA via `AbstractJpaE2eTest`)
`SyncInvocationE2eTest`:
- reply at the end (200, payload);
- early reply, process continues and completes afterwards (200 before completion; later state visible);
- failure with compensation, `REPLY_IMMEDIATELY` (502, `compensation=IN_PROGRESS`, process later COMPENSATED);
- failure with compensation, `REPLY_AFTER_COMPENSATION` (502, `COMPENSATED`) and a failing compensation (`COMPENSATION_FAILED`);
- failure with nothing to compensate (`FAILED`/`NONE`);
- deadline expired → 202, then GET (and long-poll GET) returns the reply;
- idempotency: same key while running (attaches), after reply (same reply), different body (422);
  concurrent duplicate requests create one process;
- process-level lock busy: WAIT (202 then reply after release) and FAIL (409, no instance);
- cancelled before reply; completed without reply;
- crash mid inline stretch (mirror `CrashRecoveryE2eTest:64-95`: node A dies holding an inline claim,
  node B finishes after lease expiry, reply served by B).

### Distributed / chaos (`modules/workflow-dist-e2e`, `-Pdist-e2e`, mirror DIST-06/22)
- **DIST-23** REPLY processed on another pod: gate the worker with `WorkerStub` so the worker reply
  lands on the partition owner ≠ the waiting pod; assert 200 and `wakeups{via=notify}`.
- **DIST-24** waiting pod dies (close its context mid-wait): client sees connection error; retry by
  key on the other pod returns the reply; no duplicate process.
- **DIST-25** broker outage during a sync invocation (`pauseKafka` while a Kafka-worker step is in
  flight): deadline → 202; after `resumeKafka` the reply appears via GET; nothing lost, nothing
  dead-lettered.
- **DIST-26** two shards (DIST-22 harness): invocation placed on the receiving shard; retry with the
  same key on the other shard → 202 + result served from the fleet index.
- **DIST-27** inline writer vs partition owner collision (parallel branches): exactly-once step
  execution, `@Version` rejection observed and recovered.
- **DIST-28** NOTIFY listener connection killed: waiters still complete via the poll fallback.

### Benchmark (`modules/workflow-benchmark`)
A `bench.workload=sync` driver issuing HTTP invocations at a paced rate against a 5-step saga
(START → 3 embedded ACTIONs → REPLY → END) and a kafka-worker variant. Report p50/p95/p99 of
request → response, inline vs `workflow.sync.inline.enabled=false`, single pod and two pods, with
the harness's existing caveats (§2.4 — paced below saturation, roles split for any scalability
claim).

## 5. Phases (one PR each; each is a stopping point)

- [x] **P0 — Plan & decisions.** §7 resolved (delegated to Claude by the owner, 2026-09-24).
- [x] **P1 — REPLY step + validation.** `StepType.REPLY`, `Step.replyVariables/replyExpression`,
      `WorkflowDefinition.syncInvocation` (+ entity column, both loaders, content hash),
      `Process.recordReply` + `process_entity.reply_*` (V31 part 1), `resolveReplySteps` in the
      step-over, DYNAMIC rejection; `modules/definition-analysis` with `ReplyPathAnalyzer`, wired into
      `checkInvariants()` and `ValidateMojo`; schema; graph node + bundle; plugins synced and bumped;
      `.gitignore` fix. *No API yet — a REPLY is visible in the UI Reply tab only.* — DONE:
      `StepType.REPLY`, `Step.replyVariables/replyExpression`, `SyncInvocation` record on the
      definition (entity column, both classpath loaders, directory import, content hash),
      `ProcessReply` on `Process` (V31 columns), `ReplyPayloadResolver` (compact JSON,
      `workflow.sync.max-reply-bytes`), `resolveReplySteps` in the step-over, DYNAMIC rejection,
      `modules/definition-analysis` (`ReplyPathAnalyzer`, 16 tests) wired into `checkInvariants()`,
      `topologyWarnings()` and `SpecValidator`; schema; graph node + editor fields + bundle; plugin
      versions bumped and `.gitignore` fixed. Found and fixed on the way: `StepTimeoutDefaults`
      rebuilt the definition through a narrow constructor and silently dropped `processLock`, the
      flow-authorization requirements (and now `syncInvocation`) whenever
      `workflow.default-step-timeout-ms` was set. The UI Reply tab moves to P7.
- [x] **P2 — Invocation API on the normal (async) path.** `sync_invocation` table, controller,
      idempotency, deadline → 202, GET by id/key + long-poll, `SyncReplyWaiters` with **local signal
      + batched poll only**, admission (`max-waiting`), transactional creation, basic metrics.
      Correct in every mode, today's latency. E2E happy path / early reply / deadline / idempotency.
      — DONE: `SyncInvocationController` (POST + GET by id + GET by key, `DeferredResult`, 200/202/
      502/400/404/409/422/429), `SyncInvocationService` (idempotency by (definition, key) + request
      hash, `createWith` = invocation row and process creation in ONE transaction, insert-first so a
      concurrent duplicate fails on the unique constraint and joins the winner), `SyncReplyWaiters`
      (local after-commit signal from the step-over + batched poll via
      `ProcessRepository.findRepliedAmong`), `InvocationRepository` memory/JPA + `V32`,
      `InvocationRetention`, `eventconductor.sync.*` metrics. Tests: `SyncInvocationE2eTest` (7,
      over Spring MVC), `SyncInvocationJpaE2eTest` (2, incl. 6 concurrent same-key requests → one
      process), unit tests for waiters, repository, hash, status mapping. A process that fails
      before replying still answers 202 at the deadline until P3.
- [x] **P2b — SSE progress stream.** `text/event-stream` on POST and GET (§3.4.2b): cursor over
      steps and log, `Last-Event-ID` resume, `follow`, e2e reading the stream. — DONE:
      `InvocationProgress` (snapshot differ, monotonic ids, dedupe keys, lookback resume),
      `SyncInvocationService.tick`, `SseEmitter` endpoints on the same POST/GET paths selected by
      `Accept: text/event-stream`, sharing the admission budget. Tests: `SyncInvocationStreamE2eTest`
      (4: stream ending in `reply`, `timeout`, `follow` past an early reply, resume),
      `InvocationProgressTest` (3).
- [x] **P3 — Error contract + lock policy.** `onFailure` both modes, CANCELLED /
      COMPLETED_WITHOUT_REPLY, error summary, `onLockBusy: FAIL` with `LockService.tryAcquire` and
      rollback, retry-after-failure behaviour. E2E failure matrix + lock tests. — DONE:
      `EngineReplyPolicy`, applied inside `ProcessRepository.save` (memory and JPA) — the one point
      every status transition funnels through — so the engine's answer commits with whichever
      transition decided it (step-over, status recompute, rollback handler, cancellation); the reply
      signal moved there too. Decision table in its Javadoc. One subtlety found by the e2e: in
      `REPLY_AFTER_COMPENSATION` the status recompute saves the process ERROR once more just before
      it is marked COMPENSATED, so an ERROR save only answers when there is nothing to undo.
      `LockService.tryAcquire` (never queues) backs `onLockBusy: FAIL`, taken inside the creation
      transaction. Tests: `SyncInvocationFailureE2eTest` (8), `SyncInvocationFailureJpaE2eTest` (2,
      incl. the lock row rolled back with the refusal), `EngineReplyPolicyTest` (6), `tryAcquire`.
- [x] **P4 — Fast path.** Outbox `process_id`/claim columns (V31 part 2 or V32), CAS in
      `EmbeddedOutboxRelay`, `InlineOutboxDriver` + lease/heartbeat + sweeper, inline executor +
      budget + saturation fallback, explicit row lock in kafka mode. Inline crash e2e, DIST-27,
      **benchmark before/after**. Merge only if the benchmark shows the gain. — DONE, with two
      refinements over §3.6 found while building it:
      1. **Claim at insert instead of CAS after commit.** While a pod drives a process, the rows that
         process writes on the driving thread are inserted already `InlineClaimed` (thread-scoped
         `InlineDrive` context read by the two repositories that write the outbox), so no relay ever
         sees them and there is no race to win — the relays keep claiming only `Pending`, unchanged,
         and `EmbeddedOutboxRelay` needed no CAS. Unkeyed log rows written on the driving thread are
         claimed too (so the SSE stream shows logs promptly); `MessageReceived` and
         `ProcessStatusChanged` never are (they may leave the shard).
      2. **No explicit row lock in kafka mode.** Handlers take the process lock the way they always
         do; a conflict with the partition owner fails optimistically (`@Version`) and the drive gives
         the rest back to the relay — the engine's existing answer to two writers.
      Pod identity is per engine instance, not per JVM (found by the crash test: two engines in one
      JVM renewed each other's claims). `InlineOutboxDriver` (slots = `workflow.sync.inline.threads`,
      budget, lease + renewer), `InlineClaimSweeper`, `V33`. Tests: `InlineFastPathJpaE2eTest` (relay
      switched OFF — the process can only move inline), `InlineBudgetJpaE2eTest` (rest handed back
      `Pending`), `InlineCrashRecoveryE2eTest` (pod dies inside an embedded worker with the task row
      claimed; the other node's sweeper and relay finish it and the reply is there), unit tests on H2.

      **Benchmark** (`SyncLatencyBenchmarkTest`, dist-e2e, `-Dbench.sync=true`; PostgreSQL + Kafka in
      containers on one developer machine, 200 sequential invocations, poll 200 ms — read the ratios):

      | pods | definition | inline | p50 | p95 | p99 |
      |---|---|---|---|---|---|
      | 1 | engine-internal steps only | off | 20.9 ms | 34.1 ms | 39.1 ms |
      | 1 | engine-internal steps only | **on** | **12.5 ms** | **18.1 ms** | **20.8 ms** |
      | 1 | two Kafka-worker steps | off | 18.7 ms | 27.8 ms | 32.5 ms |
      | 1 | two Kafka-worker steps | **on** | **14.0 ms** | **18.8 ms** | **20.7 ms** |
      | 2 | engine-internal steps only | off | 12.0 ms | 237.3 ms | 251.2 ms |
      | 2 | engine-internal steps only | **on** | **13.7 ms** | **18.0 ms** | **20.3 ms** |
      | 2 | two Kafka-worker steps | off | 18.7 ms | 251.1 ms | 251.4 ms |
      | 2 | two Kafka-worker steps | on | 17.4 ms | 251.1 ms | 251.7 ms |

      The two-pod tail without the fast path is the cross-pod relay poll plus the reply poll. With
      Kafka workers it remains even inline: the worker's reply lands on the partition owner, and the
      waiting pod hears only on its 250 ms poll — precisely what P5 (NOTIFY) removes.
- [ ] **P5 — Cross-pod wake-up.** `LISTEN/NOTIFY` listener (PG dialect), notify in the reply
      transaction, `wakeups` metric. DIST-23/24/25/28.
- [ ] **P6 — Sharding.** Local placement for sync starts in `IngressRouter`, reply projected to the
      process index, cross-shard retry/GET. DIST-26.
- [ ] **P7 — Tracing + UI.** Span links both ways, Synchronous badge, Reply tab details, dashboard
      panel.
- [ ] **P8 — Docs + demo.** `guides/synchronous-invocation.md` next to `guides/starting-a-process.md`
      (sidebar `doc/astro.config.mjs:68-80`), REPLY in `reference/step-types.md`, `workflow.sync.*` in
      `reference/configuration.md`, CHANGELOG; a sync booking definition in `ec-definitions` and the
      `ec-demo1` wiring (cross-repo / deploy steps left to a human release, as LOCK P8).

P2 before P4 is deliberate: the API and its guarantees are correct without the fast path, so the
fast path is a pure latency optimization that can be measured, switched off
(`workflow.sync.inline.enabled`), and reverted independently.

## 6. New configuration (summary)

| property | default |
|---|---|
| `workflow.sync.enabled` | `true` (endpoint present; definitions still opt in) |
| `workflow.sync.max-deadline-ms` | 30000 |
| `workflow.sync.max-waiting` | 200 |
| `workflow.sync.poll-interval-ms` | 250 |
| `workflow.sync.retention` | `24h` |
| `workflow.sync.inline.enabled` | `true` |
| `workflow.sync.inline.threads` | 8 |
| `workflow.sync.inline.max-steps` | 64 |
| `workflow.sync.inline.claim-lease-ms` | 30000 |
| `workflow.sync.notify.enabled` | `true` on PostgreSQL |
| `workflow.sync.max-reply-bytes` | 262144 |

## 7. Decisions — RESOLVED (2026-09-24)

Delegated by the owner ("decide tú"); each one is the recommendation from the draft, with the
reasoning kept so it can be revisited.

1. **Lock busy (D1).** Process-level `onLockBusy` defaults to **`WAIT`**; `FAIL` is opt-in and
   rolls back the creation (409, no instance). Step-level LOCK **always waits** in v1. *Why:* the
   deadline already bounds the client's wait, FIFO fairness is preserved, and the same definition
   keeps the same meaning whether it is started sync or async.
2. **Completed without REPLY (D2).** Build/import **warning**, not an error; runtime outcome
   `COMPLETED_WITHOUT_REPLY`, HTTP **200** with `reply: null`. *Why:* an error would force a REPLY
   onto every CHOICE branch, including ones the author deliberately leaves silent; the outcome
   field makes the case explicit to the client.
3. **Kafka-mode fast path (D3).** **Engine-internal steps only** in v1; no hybrid dispatch.
   *Why:* hybrid changes the deployment topology (task code inside orchestrator pods) and deserves
   its own decision once P4's benchmark shows how much is left on the table. `InlineOutboxDriver`
   is built so a local-handler check can be added later without redesign.
4. **HTTP status (D4).** `REPLIED` and `COMPLETED_WITHOUT_REPLY` → **200**; every failure outcome
   (`FAILED`, `COMPENSATED`, `COMPENSATION_FAILED`, `CANCELLED`) → **502** with the envelope.
   REPLY **cannot** set the HTTP status in v1: a business "no" is a REPLY payload on its CHOICE
   branch, returned with 200. *Why:* one simple rule — 502 means "a step the engine orchestrated
   did not get to a reply" — distinct from engine faults (500), lock busy (409) and overload (429).
5. **`onFailure` default (D5).** **`REPLY_IMMEDIATELY`**. *Why:* the client is not held hostage by
   a rollback of unknown length; `compensation: IN_PROGRESS` tells it exactly what is happening,
   and the final state is always available via GET.
6. **Callback URL (D6).** **Deferred.** The definition can notify durably with an ACTION or
   SEND_MESSAGE after the REPLY; a webhook dispatcher (retries, signing, SSRF) is its own feature.
7. **Retry after a failure reply (D7).** **First reply wins**, for GETs too. The GET envelope's
   `processStatus` shows the operator's recovery; a later REPLY is logged, not recorded.
8. **Sharding (D8).** Sync invocations are **placed on the receiving shard**; a retry that lands on
   another shard gets **202** and the result is served from the fleet process index.
9. **`modules/definition-analysis` (D9).** **Add it** in P1 with `ReplyPathAnalyzer` only. Moving
   the existing drifted invariants (single START, TIMER/message/`lockKey`) into it is a
   **separate follow-up PR** after P1, to keep P1 reviewable.
10. **API shape (D10).** `Idempotency-Key` **required** (400 without it); wait expressed with
    **`Prefer: wait=<seconds>`** (RFC 7240), no body/query alternative; paths as in §3.4.1.
11. **Reply size (D11).** Capped by `workflow.sync.max-reply-bytes` (default **256 KB**); a REPLY
    whose serialized payload exceeds it fails the step (`ERROR`, normal failure path), so an
    oversized reply is visible and compensable rather than silently truncated.

## 8. Key file anchors

Engine (`modules/workflow-engine/src/main/java/io/mateu/workflow/`):
`domain/aggregates/StepType.java:7-18` · `Step.java:30-253,278-309` ·
`WorkflowDefinition.java:26-114,371-548` · `Process.java:46,131-144` · `ProcessStatus.java` ·
`StepExecution.java:246-420` · `domain/services/WorkflowOrchestrationService.java:46-128,408-479,537-546` ·
`CompensationService.java:33-104` · `LockKeyResolver.java:32-51` ·
`application/services/JEXLEvaluator.java` · `WorkflowDefinitionValidator.java:76-123` ·
`WorkflowDefinitionVersioningService.java:82-106` · `IngressRouter.java:28-149` ·
`application/usecases/process/create/CreateProcessUseCase.java:75-210` ·
`application/usecases/process/stepover/StepOverProcessUseCase.java:58-206` ·
`application/usecases/stepexecution/start/StartStepExecutionUseCase.java:42-90` ·
`application/usecases/stepexecution/update/UpdateStepExecutionUseCase.java:156-213` ·
`infra/in/async/processdomainevent/domaineventhandlers/StepExecutionStatusUpdatedEventHandler.java:61-199` ·
`infra/in/async/OrchestratorKafkaConsumerConfig.java:59-183` ·
`infra/in/rest/MessageRestController.java` · `infra/out/async/OutboxSignal.java` ·
`OutboxRelay.java` · `OutboxDrain.java` · `EmbeddedOutboxRelay.java:55-111` · `RelayDestination.java` ·
`EmbeddedDownstreamEventPublisher.java:95-275` · `infra/out/persistence/OutboxMessageEntity.java` ·
`JdbcProcessLockService.java:52-94` · `PartitionOwnedProcessLockService.java` · `JdbcLockService.java` ·
`PostgresDbLockDialect.java` · `infra/in/scheduler/TimeoutScheduler.java` ·
`infra/in/startup/InFlightStepRearmRunner.java` · `application/services/ProcessTrace.java` ·
`autoconfigure/MicrometerWorkflowMetrics.java` · `infra/in/ui/pages/process/SimpleProcessViewModel.java:100-214`.
Migrations: `resources/db/migration/workflow/` (latest `V30__task_contracts.sql`, next `V31`).
Worker: `modules/worker-embedded/.../WorkerEmbeddedAutoConfiguration.java:32-111` ·
`modules/worker-api/.../TaskDispatcher.java:48-101` · `TransactionAwareReplySink.java:42-52`.
Schema/plugins: `workflow-definition-schema.json:74,246-265,402,539-670` ·
`plugins/vscode-eventconductor/scripts/sync-assets.js:19-32` ·
`plugins/intellij-eventconductor/build.gradle.kts:93-113` ·
`modules/workflow-maven-plugin/.../ValidateMojo.java:144-242`.
Graph: `frontend/src/eventconductor-workflow-graph.ts:50-53,198-202,214-235,286,3323+`.
Tests: `modules/workflow-e2e/.../CrashRecoveryE2eTest.java:64-95` · `LockSerializationE2eTest` ·
`CompensationE2eTest` · `IdempotencyAndSecurityE2eTest` ·
`modules/workflow-dist-e2e/.../support/DistInfra.java` · `Dist22ShardedMessageRoutingChaosTest.java` ·
`modules/workflow-benchmark/.../BenchmarkReport.java:33-80`.
Docs: `doc/src/content/docs/guides/performance.md:45-160` · `guides/sharding.md` ·
`guides/starting-a-process.md` · `reference/step-types.md` · `reference/configuration.md`.
