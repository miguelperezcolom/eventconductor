# Plan: synchronous updates of running processes (2.33)

> Status: **IMPLEMENTED** on branch `feat/process-updates`, in one PR. Deviations from the draft are
> noted inline.

## 1. Goal

Parity with Temporal *Workflow Updates* and *update-with-start*, DSL-first: a caller writes to a live
process, the write is **validated**, applied **atomically under the process lock**, **recorded**, and
answered with a **result** — over HTTP, MCP, Kafka and the test kit.

| | message (`WAIT_FOR_MESSAGE`) | sync invocation (`REPLY`) | instance operation (`/variables`, …) | **update** |
|---|---|---|---|---|
| who | another system | a caller starting a process | an operator | a caller of a live process |
| validated | correlation only | definition + input limits | rules + reason | JSON Schema + guards |
| answer | 202, fire-and-forget (dropped if nobody waits) | the process's REPLY, once | 202 | 200 + result, **any number of times** |
| declared in the DSL | yes | yes | no (admin) | yes (`updates`) |
| recorded | no | invocation row | audit log line | `process_update` row + log line |

## 2. What already exists (and what it means here)

- **Commands to a live process** go through `CommandDispatcher` as upstream `*Requested` events
  (`SetProcessVariablesRequested`, …), handled on the node that owns the process (Kafka partition or
  shard) under `ProcessLockService.runExclusivelyOrThrow`. In embedded mode the publisher runs the
  handler inline. ⇒ an update is one more such command: `ProcessUpdateRequested`.
- **Changing variables safely** is solved by `SetProcessVariablesUseCase`: write, `rearm` message
  subscriptions/timers, then step over a RUNNING process so a step held by a link guard is released.
  ⇒ an update reuses exactly that sequence.
- **Completing a waiting step from outside** is `CompleteMessageStepHandler.doHandle`: PENDING and
  started, mark COMPLETED, save (the status change drives the flow), `rearm`, metrics, log line.
- **Sync invocation** gives the HTTP contract: `Idempotency-Key`, `Prefer: wait=N` (RFC 7240, capped
  by `workflow.sync.max-deadline-ms`), `202 + Location` on deadline, admission slots
  (`workflow.sync.max-waiting`), problem+json refusals, `workflow.sync.retention`, a waiter woken
  locally on commit and by a poll across pods.
- **REPLY is at most once** (`Process.recordReply`). Updates must not reuse it: their results live in
  their own rows.
- **JSON Schema 2020-12** is already compiled and cached by `JsonSchemas` (networknt 3.x).
- The **definition snapshot** on the process (`workflowDefinitionJson`) is what a process runs; a
  migration (#551) swaps it. ⇒ an update is looked up in the *snapshot*, never in the latest version.

## 3. Design

### 3.1 DSL — decision: declarative update handlers on the definition (+ `completes`)

```yaml
updates:
  - name: changeAddress
    description: Change the shipping address before the parcel leaves.
    accepts:                       # JSON Schema (2020-12) of the payload — 400 when it does not hold
      type: object
      required: [address]
      properties: { address: { type: string, minLength: 5 } }
    acceptedWhile: [awaitShipping] # optional: accepted only while one of these steps is waiting — else 409
    validate:                      # optional JEXL guards over payload + variables — 422 with the message
      - expression: "payload.address != address"
        message: "That is already the shipping address."
    set:                           # variables written: name → JEXL over payload + variables
      address: payload.address
      addressChanges: "addressChanges == null ? 1 : addressChanges + 1"
    completes: awaitShipping       # optional: a WAIT_FOR_MESSAGE the update resumes, so the flow moves on
    reply: "{'address': address, 'changes': addressChanges}"   # JEXL → JSON result (or replyTemplate)
    sensitive: [cardNumber]        # payload fields never shown (UI/REST/MCP read the masked copy)
```

Evaluation order, all in **one transaction under the process lock**: process accepting? →
`acceptedWhile` / `completes` step waiting? → `validate` guards (first false refuses) → `set`
evaluated against the *pre*-update state (all computed before anything is written) → `reply`
evaluated against the *post*-update state → write variables, complete the `completes` step, record
the result. A refusal (409/422) or an evaluation error changes nothing.

**Why not a new `ON_UPDATE` step type / handler branch.** A branch that runs "possibly several times,
concurrently with the main flow" needs several executions of one step id inside one process, a reply
that is produced later by an `UPDATE_REPLY`, and a way to route each reply to its own caller. The step
model is one execution per step id (loops/forEach have bespoke iteration bookkeeping) and REPLY is
deliberately once-only; bolting both on would touch the orchestrator, the validator's reachability
analysis, migration and the UI's step history — for a capability Temporal itself steers away from
(long-running update handlers are discouraged: an update should validate, mutate, answer). The
declarative handler gives Temporal's guarantees — validator rejections leave no trace in the process,
ordering per process, a durable result — with no new step semantics. When the update must *drive* the
flow, `completes` resumes a waiting `WAIT_FOR_MESSAGE` (the same step can still be resumed by its
message), and everything downstream is ordinary steps.

**Why not WAIT_FOR_MESSAGE + REPLY with an `update` flag.** The answer would have to come from a REPLY
reached later, which is once-only and per-process, so a second update could never be answered; and the
caller would wait on whatever runs between the wait and the REPLY. Deferred (§6): "reply after step X"
for updates that genuinely need worker steps before answering.

### 3.2 API

```
POST /workflow/api/processes/{id}/updates/{name}                 Idempotency-Key?, Prefer: wait=N   body = payload
POST /workflow/api/processes/by-key/updates/{name}?businessKey=K  (same)
POST /workflow/api/definitions/{definitionId}/update-with-start  {businessKey, variables?, update, payload}
GET  /workflow/api/updates/{updateId}                              Prefer: wait=N long-polls
GET  /workflow/api/processes/{id}/updates                          the process's updates, oldest first
```

| answer | when |
|---|---|
| `200` `{updateId, processId, name, status: COMPLETED, result, …}` | applied; `result` is the reply JSON, verbatim |
| `400` | payload is not JSON / fails `accepts` / over input limits — nothing recorded |
| `404` | no such process, or its definition version declares no such update |
| `409` `reason: NOT_ACCEPTING` | process not RUNNING/PENDING, or the `acceptedWhile`/`completes` step is not waiting |
| `422` `reason: REFUSED` | a `validate` guard said no (its message is the detail) — nothing changed |
| `422` `reason: IDEMPOTENCY_KEY_REUSED` | the key was used for a different payload/update |
| `500` `reason: FAILED` | a `set`/`reply` expression failed — nothing changed |
| `202 + Location: /workflow/api/updates/{id}` | the deadline passed first; the update still completes durably |
| `429` | admission slots exhausted (shared with sync invocation's `workflow.sync.max-waiting`) |

Every recorded outcome (200/409/422/500 from the handler) is replayed to a retry with the same key.
`update-with-start` answers the same envelope plus `started: true|false`.

### 3.3 Execution path

1. **Door** (`ProcessUpdateService.submit`): resolve the process (id or business key), flow
   authorization of its definition, find the update in the **snapshot**, validate the payload against
   `accepts` (400), fingerprint `(name, canonical payload)`; with a key, a recorded update with that
   key is returned (or 422 on a different fingerprint; a still-`REQUESTED` one is re-dispatched — the
   re-drive for a lost command). A cheap pre-check answers 409 for a process that is plainly not
   accepting without recording anything. Then a `REQUESTED` row is inserted (unique
   `(process_id, idempotency_key)`; a concurrent duplicate joins the winner) and
   `ProcessUpdateRequested` is dispatched.
2. **Owner** (`ApplyProcessUpdateUseCase`, from `ProcessUpdateRequestedEventHandler`): under the process
   lock, re-reads the row (not `REQUESTED` → nothing to do: idempotent under redelivery), evaluates
   §3.1, writes process + step + row in the lock's transaction, then (outside the evaluation) re-arms
   and steps over like `SetProcessVariablesUseCase`. A command arriving with no row (produced straight
   to the `upstream` topic) creates it — schema validation included — so the Kafka command is a door of
   its own.
3. **Waiter** (`ProcessUpdateWaiters`): woken on commit locally, polled across pods
   (`workflow.sync.poll-interval-ms`), timer at the deadline → 202.

Embedded mode runs step 2 on a small executor rather than the request thread, so a process lock held
longer than the deadline turns into a 202 instead of a blocked request (retried up to 3 times on lock
contention; a row left `REQUESTED` is re-driven by a retry with the same key). Kafka mode publishes
synchronously (a broker that is down is a 503, as for messages); sharded fleets use the command topic.

**Ordering.** Per process, updates are applied one at a time under the process lock, in the order
they reach the owner (Kafka partition order in kafka mode). Each row records `completedAt`; the UI and
`GET …/updates` list them in that order.

### 3.4 Update-with-start

`UpdateWithStartPolicy` decides where an update-with-start goes — a deliberately small interface so the
business-key policy work (continue-as-new / key reuse, developed in parallel) can replace it:

```java
Resolution resolve(WorkflowDefinition definition, String businessKey);  // EXISTING(process) | START | CONFLICT(why)
```

Default: a process with the key of the same definition → `EXISTING` (whatever its status: a finished
one answers 409 through the normal path, since keys are not reused today); of another definition →
`CONFLICT` (409); none → `START`.

`START` is atomic: the update is evaluated against the *candidate* process (initial variables), its
`set` seeds the initial variables, and the process, its `COMPLETED` update row and the result are
written in one transaction — so the update is the first thing the process sees, before START runs.
`acceptedWhile` and `completes` are not evaluated on this branch (nothing is waiting yet). A concurrent
start that loses the key race falls back to `EXISTING`. A refused update (422) or invalid payload (400)
starts nothing.

### 3.5 Durability — `V55__process_update.sql`

`process_update(id, process_id, workflow_definition_id, update_name, idempotency_key, request_hash,
payload, status, result, error, requested_by, with_start, requested_at, completed_at, expires_at)`,
unique `(process_id, idempotency_key)`, indexed by `process_id` and `expires_at`; plus
`workflow_definition_entity.updates_json`. V55 because main is at V50, #557 holds V53 and V54 is left
for the flow-control work running in parallel.

- `payload` is the **masked** copy (fields in `sensitive` → `****`): the raw payload only travels in the
  command, like `SetProcessVariablesRequested`'s values. Nothing logs a payload or a result.
- `request_hash` is SHA-256 over `name + canonical payload` (as the sync invocation's on main; when #557
  lands, both switch to its keyed `VariableEncryption.fingerprint`).
- Rows live as long as the process (purged with it by the retention purge). The **idempotency key**
  lives `workflow.sync.retention`: the retention pass (`InvocationRetention`) then rewrites the key to
  the row id, freeing it without losing the history.
- A request with no `Idempotency-Key` gets the row id as key (no retry dedupe).

### 3.6 Surfaces

- **MCP** (`ProcessUpdateMcpTools`): `updateProcess`, `updateWithStart`, `listProcessUpdates`.
- **Kafka**: `process-update-requested` on `upstream` (keyed by process id), documented in
  kafka-topics; `UpstreamInputGuard` limits it.
- **OpenAPI**: the new controller is annotated; `doc/public/openapi/eventconductor-api.yaml`
  regenerated by `EngineOpenApiSpecTest`.
- **UI**: process detail tab *Updates* — time, name, caller, status, masked payload, result/error.
- **Test kit**: `runtime.update(process, "changeAddress", payload)` → `UpdateOutcome` (status,
  `result()`, `resultAs`, `error()`), `runtime.updateWithStart(definitionId, businessKey, variables,
  name, payload)`, `assertThat(process).hasUpdate("changeAddress")` / `update(outcome).isCompleted()…`.
- **Schema**: `updates` in `workflow-definition-schema.json` (the Maven plugin picks it up at build);
  load-time invariants in `WorkflowDefinition.checkUpdates` (unique names, `accepts` is valid JSON
  Schema, steps named exist, `completes` is a WAIT_FOR_MESSAGE, expressions parse, one of
  `reply`/`replyTemplate`).
- **Versioning**: `updates` is part of the content hash (it changes what a process accepts).

### 3.7 Sensitive variables (#557, merged before this PR)

- The fingerprint is `VariableEncryption.fingerprint` (keyed HMAC `h1:<keyId>:…`), verified with
  `fingerprintMatches` (rotation-safe, legacy digests still verify).
- Protected payload fields — declared `sensitive`, named like a sensitive variable, or read by a `set` that
  writes a sensitive variable — are sealed (masked without keys) in `process_update.payload` and in the
  `process-update-requested` command; the owner opens them. `set` into a sensitive variable is sealed by the
  process repository like any write.
- `validate` / `reply` / `replyTemplate` reading a sensitive variable is refused at load unless the update
  declares `allowSensitiveVariables: true`; at runtime they do not see sensitive variables without it.
- An opted-in result is recorded sealed (whole) and opened only for the caller (the POST, or a retry with its
  key); GET, listings, MCP and the console mask sealed values and result members named like a sensitive
  variable.

## 4. Tests

- Unit: `UpdateEvaluator` (guards, set before/after, reply, failures), definition invariants, JSON
  Schema refusal, controller mapping (MockMvc), waiter.
- e2e memory + JPA (H2) through REST: accepted, refused by schema, refused by guard, not accepting
  (terminal, step not waiting), several updates in order, concurrent updates serialized, idempotent
  retry (+ key reused), deadline 202 then completion, update-with-start both branches, compensation
  interplay (refused while ERROR/compensating; the compensation sees the updated variable).
- PostgreSQL (Testcontainers) for the new table and the concurrent path.
- Test kit test in `workflow-test`.
- OpenAPI drift test; browser suite (Updates tab).

## 5. Rollout

Additive: a definition without `updates` behaves exactly as before; the new endpoints answer 404 for
it. No new configuration (reuses `workflow.sync.*`).

## 6. Deferred

- **Reply after downstream steps** (an update that starts worker steps and answers when a later step
  completes) — the `UPDATE_REPLY` idea; needs per-update reply routing.
- **IDE plugins** schema sync + release (schema-driven; ride the next plugins release).
- **Cross-shard by-key routing** of updates: a sharded fleet answers 404 for a process on another
  shard unless the caller addresses the owning shard (as messages/commands by id already route).
