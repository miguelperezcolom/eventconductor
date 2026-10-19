# Plan: migrating running processes to another version of their definition

> Status: **DECIDED (2026-10-19)** — implementation on `feat/instance-migration` (2.31), based on
> `feat/search-attributes-batch-ops` (#547, not merged yet) for the batch operations it extends.
> Builds on the manual operations of #545 (`ProcessOperationRules`, `OperatorAudit`, the
> `*Requested` command events), the loops of #543 (current executions, superseded passes, forEach
> containers) and the version history of `WorkflowDefinitionVersioningService`.

## 1. Goal

A process freezes the definition it was started with: the definition snapshot on the process
(`Process.workflowDefinitionJson`) and the step copy on each step execution (`StepExecution.stepJson`).
Deploying a new version never touches a process in flight — which is right, and also means a buggy
definition strands long-running sagas with no way forward but cancel + restart.

This adds the way forward: **move a live process onto another version of its definition**, keeping
everything it has done. Parity with Camunda's process instance migration (single and batch, a
mapping of activities, validation before anything changes) and with Temporal's worker-versioning
upgrade (a check in CI that the old version's in-flight work has somewhere to go), DSL-first: the
rules read the definitions, and the same rules run in the engine, in the Maven plugin and in CI.

## 2. The operation

```
MigrateProcessUseCase(processId, targetVersion, stepMapping, variableMapping, reason)
```

- `targetVersion` — a recorded version of the process's own definition (JPA keeps the history;
  `null` means *the definition as it is now*, the only choice in memory mode). Migrating to another
  definition id is out of scope.
- `stepMapping` — source step id → target step id. Defaults to the same id when the target has it
  with a compatible type. An explicit entry overrides the default; mapping to `""` says *no
  counterpart* (the step's execution becomes history).
- `variableMapping` — variables to set on the process as part of the migration (name → value), for
  the ones the target needs and the process does not have. Applied before anything is re-armed.
- `reason` — mandatory, audited, as for every manual operation.

Like #545's operations it is **requested at the door and carried out by the node that owns the
process, under the process lock** (`MigrateProcessRequested`, an additive upstream command event),
and re-planned there: the process may have moved on since the door looked.

**Dry run**: the same planner, returning the plan (the effective mapping, what happens to every step,
the problems) without changing anything. The door runs it before dispatching and answers `409` with
the problems when it would be refused.

## 3. Semantics, per step state

The plan is made over the process's **current** executions (one per step; a loop's earlier passes and
forEach iterations are handled with their step). Each source step gets one action:

| Source state | Mapped | Action |
|---|---|---|
| not started (CREATED) | yes | **REMAP** — the execution takes the target step's id and frozen copy |
| not started | no | **DROP** — cancelled quietly and kept as history (nothing ran) |
| COMPLETED, branch not taken, routed TIMEOUT | yes | **REMAP** — history kept, now under the target id |
| COMPLETED … | no | **HISTORY** — superseded (`DISCARDED`), visible in the timeline, out of the flow |
| in flight at a worker (ACTION, HTTP_CALL, LLM_CALL, RULE, USER_TASK, PROCESS) | yes, task contract unchanged | **KEEP_TASK** — the same execution id and dispatch number: the worker's answer still lands |
| in flight at a worker | yes, contract changed | **REDISPATCH** — `TaskCancellationRequested` to the old destination (a PROCESS child is cancelled), reset to CREATED with a new dispatch number (a late reply is dropped), dispatched again under the target |
| waiting: TIMER, WAIT_FOR_MESSAGE / onEvent / awaitReply, AWAITING_RETRY, WAITING_ON_LOCK, a forEach container | yes | **REARM** — remapped, then its deadline and message subscription recomputed from the target step (a timer keeps its start: a longer duration extends it, a shorter one may make it due now; a changed message name or correlation re-subscribes, announced with `MessageSubscriptionChanged`); an AWAITING_RETRY keeps its backoff wake |
| ERROR / unrouted TIMEOUT (a failed process) | yes | **REMAP** — still failed; a retry then runs the target's step |
| any active or failed state | no | **refused** |
| injected by a DYNAMIC step | — | **CARRY** — runtime growth the definitions do not know; kept as it is |
| — (target step with no source) | — | **ADD** — a new CREATED execution, runs when its preconditions hold |
| — (target step with no source, upstream of a step the process already reached) | — | **ADD_PASSED** — recorded COMPLETED without running (nothing to compensate), so it neither runs late nor blocks the flow |

What a *task contract* is: the fields that decide what a worker is asked to do — type, `topic`,
`task`, `formId`, `ruleId`, `childWorkflowDefinitionId`, `http`, `llm`. A changed timeout or retry
count is not a contract change: the kept task simply gets the target's deadline and budget.

**forEach**: an active container is REARMed with its iterations — every live iteration takes the target
id and copy; one at a worker is kept or re-dispatched by the same contract rule (a re-dispatched
iteration keeps its item). The target must also be a forEach over the same `items` expression;
anything else is refused (the item list was materialised from the old one).

**Loops**: the current pass is migrated by its state; earlier passes (superseded) follow their step's
mapping — renamed, so the loop's caps still count them — and keep their own frozen copies.

**Locks**: a process inside a step-level critical section (its LOCK completed, no UNLOCK yet) or
queued at a LOCK keeps the lock only if the LOCK step maps to a LOCK of the same `lockName` and
`lockKey`; otherwise refused. A process that holds or queues for its definition's `processLock` is
refused when the target's `processLock` names another lock; a target that adds one takes it at the
next step-over, like a restart.

**Compensation**: refused while a saga rollback is under way, and once any compensation step has
run. A completed compensable step with no counterpart is history and is not compensated by a later
rollback — a warning in the plan says so.

**The process afterwards**: the snapshot swapped to the target (`workflowDefinitionJson`,
`workflowDefinitionVersion`), completion recomputed, a `ProcessMigrated` domain event and a
`ProcessStatusChanged` (forced, so the process index learns the new version even though the status did
not change) on the outbox, the step events of the re-armed/re-dispatched steps, the audit line, and a
step-over for a RUNNING process (new steps may be ready). A PAUSED process stays paused, its
re-armed timers frozen until resume; an ERROR process stays ERROR — migrate, then retry.

## 4. Validation (shared rules: `definition-analysis` `MigrationRules`)

Pure JDK, over the generic (parsed JSON/YAML) form of the two definitions plus runtime facts, so the
engine, the Maven plugin and CI run the same code.

Errors (refuse):
1. The process is not RUNNING, PAUSED or ERROR.
2. Saga rollback under way, or a compensation step already run.
3. An explicit mapping names a source step the process does not have, or a target step the target does
   not have; two source steps mapped onto one target step.
4. A mapped pair whose types are not compatible. Compatibility classes: worker tasks {ACTION,
   HTTP_CALL, LLM_CALL, RULE}; every other type only with itself (USER_TASK, PROCESS, TIMER,
   WAIT_FOR_MESSAGE, SEND_MESSAGE, PUBLISH_EVENT, REPLY, LOCK, UNLOCK, FORK, JOIN, CHOICE, START, END,
   DYNAMIC).
5. An active or failed step without a counterpart.
6. An active step whose target preconditions are not satisfied by what the process has completed
   (an AND join needs all of them, an XOR join one) — it would be running before its inputs.
7. An active forEach container whose target is not a forEach over the same items.
8. Locks, as above.
9. A variable an active (re-armed) step reads right now — a timer's `untilVariable`, a correlation
   expression, a lock key — that the process does not have and the variable mapping does not give.

Warnings (reported, allowed): variables read by steps still to run that the process does not have;
completed compensable steps dropped; task contracts changed (re-dispatch); steps added upstream of
where the process is (ADD_PASSED); a target `processLock` the process did not have.

**Definition-level check (CI)**: without a process, every step of the old version is checked for a
compatible counterpart (by id or the mapping file). Errors for steps a process can be *waiting in*
(everything but START, END, FORK, JOIN, CHOICE, REPLY, SEND_MESSAGE, UNLOCK and PUBLISH_EVENT without
`awaitReply`), warnings for the instantaneous ones and for contract changes.

## 5. Doors

- **REST**: `POST /workflow/api/processes/{id}/migrate` (`202`, `409` with the problems),
  `POST /workflow/api/processes/{id}/migrate/dry-run` (`200`, the plan). Body: `targetVersion`,
  `stepMapping`, `variableMapping`, `reason`. Committed OpenAPI spec regenerated.
- **Batch**: type `MIGRATE` in #547's batch operations, parameters `targetVersion`, `fromVersion`
  (processes on another version are skipped as not applicable), `map.<source>=<target>`,
  `var.<name>=<value>`, `reason`. Filtered by definition + status as any batch; each process planned
  and refused on its own (counted skipped/not applicable), the same mapping for all.
- **MCP**: `migrateProcess`, `dryRunProcessMigration`; batch MIGRATE through the batch tool.
- **Kafka**: `MigrateProcessRequested` upstream command (additive), `ProcessMigrated` domain event.
- **Admin UI**: process detail "Migrate…" — target version, mapping editor (`source=target` lines
  pre-filled with the default mapping), variables, reason, a *Preview* (dry run) and *Migrate*;
  definition page "Migrate instances…" — creates a MIGRATE batch from version N.
- **Maven plugin**: `eventconductor:check-migration -Dfrom=<git ref | path> [-Dto=<git ref | path>]
  [-Dmapping=<file>]` — for each workflow in both, the definition-level check; fails the build on
  errors. A git ref reads `git show <ref>:<file>` of the same file (the "replay in CI" of Temporal).

## 6. Audit, metrics, security

`[migrate] by <operator>: migrated from v3 to v5 (mapping a→a, b→b2, old→—); …` in the process log
(Operations tab), `eventconductor.processes.operations{operation="migrate"}`; flow authorization plus
`workflow.security.operations.required-roles`, like every manual operation. All time reads through
`EngineClock`.

## 7. Not in this change

- Migrating to a different definition id.
- Variable *transformations* (rename / compute) — set values, or use set-variables before/after.
- Mapping one source step onto several targets (split) or several onto one (merge).
- Migrating the children of PROCESS steps along with the parent (each child is its own process).
- No Flyway migration: the change needs no schema (a migration only rewrites existing columns).
