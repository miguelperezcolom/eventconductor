---
title: Migrating running processes
description: Move live processes onto another version of their workflow definition — one at a time or in a batch — with a step mapping, a dry run, and a check in CI that the old version's in-flight work has somewhere to go.
---

A process freezes the definition it was started with: a copy of the definition on the process, and a copy of each step on its step executions. Deploying a new version never touches a process in flight, which is what you want — until the version they run has a bug, and a saga that waits for weeks is stranded on it with no way forward but cancel and restart.

**Migration** is the way forward: it moves a live process onto another version of its definition and keeps everything it has done. It is EventConductor's counterpart of Camunda's process instance migration (one process or a batch, a mapping of activities, validation before anything changes) and of Temporal's upgrade of a running workflow to a new worker version (with a check in CI, see [below](#in-ci-check-migration)) — DSL-first: the rules read the two definitions, and the same rules run in the engine, in its dry run and in the Maven plugin.

## When to use it

- A version has a bug in a step the process has **not reached yet** — a wrong guard, a missing step, a step that should go to another worker. Migrate, and the process runs the fixed version from where it is.
- A step **in flight** has the wrong contract (wrong topic, wrong task, wrong form). Migrate with that step mapped onto the fixed one: its task is cancelled and dispatched again under the new contract.
- A timer or a message wait is wrong — too long, the wrong message, the wrong correlation key. Migrate: the wait is re-armed against the new version.

It is not the tool for undoing what a process has **done** — that is [retry from a step](/guides/operating-running-processes/#retry-from-step), or a compensation. And it is refused while a saga is rolling back.

## Running it

From the process detail page (**Migrate…**: the version, the step mapping pre-filled with the default one, the variables, the reason, a **Preview** and **Migrate**), the [REST API](/reference/processes-rest-api/#migration), the [MCP tools](/guides/mcp-overview/) or a Kafka command on `upstream`:

```http
POST /workflow/api/processes/{id}/migrate/dry-run
{"targetVersion": 5, "stepMapping": {"charge": "charge-card"}}

POST /workflow/api/processes/{id}/migrate
{"targetVersion": 5, "stepMapping": {"charge": "charge-card"}, "variableMapping": {"currency": "EUR"},
 "reason": "v5 charges through the new gateway"}
```

- `targetVersion` — a recorded version of the process's own definition. Left out, the definition **as it is now**. With `workflow.persistence=jpa` every content change of a definition records a version, so a process can be moved to any of them — back to an older one too. In memory mode there is no history: only the current version.
- `stepMapping` — source step id → target step id. Every step without an entry maps onto the step of the **same id**, when the target has one of a compatible type. An entry with an empty target says the step has no counterpart.
- `variableMapping` — variables the migration sets (name → value), for what the target needs and the process does not have. They are written before anything is re-armed.
- `reason` — mandatory, as for every manual operation.

Like the other [manual operations](/guides/operating-running-processes/#how-an-operation-runs), the door plans the migration and refuses it with `409` and the problems when it cannot be done; otherwise it publishes `migrate-process-requested` and the node that owns the process carries it out **under the process lock**, planning it again — the process may have moved on in between. A migration refused at that point is written to the process log with the reason.

## The dry run

`POST …/migrate/dry-run` (the **Preview** button, the `dryRunProcessMigration` MCP tool) answers the plan and changes nothing:

```json
{
  "processId": "6c1f…", "workflowDefinitionId": "booking", "fromVersion": 3, "toVersion": 5,
  "executable": true,
  "mapping": {"start": "start", "reserve": "reserve", "charge": "charge-card", "end": "end"},
  "steps": [
    {"sourceStepId": "reserve", "targetStepId": "reserve", "state": "COMPLETED", "action": "REMAP"},
    {"sourceStepId": "charge", "targetStepId": "charge-card", "state": "ACTIVE", "action": "REDISPATCH",
     "note": "contract changed: cancelled and dispatched again"},
    {"sourceStepId": null, "targetStepId": "notify", "state": null, "action": "ADD",
     "note": "new: runs when its preconditions hold"}
  ],
  "problems": [
    {"severity": "WARNING", "stepId": "charge", "message": "the task of 'charge' in flight is cancelled and dispatched again as 'charge-card': its contract changed"}
  ]
}
```

Any problem of severity `ERROR` refuses the migration; warnings are reported and let through.

## What happens to each step

The plan is made over the process's current executions — one per step; a loop's earlier passes and a forEach's iterations move with their step.

| The step is… | Mapped | It is… |
|---|---|---|
| not started | yes | **REMAP** — it takes the target step's id and copy, and runs when its preconditions hold |
| not started | no | **DROP** — cancelled before it ran, kept as history |
| completed, a branch not taken, a timeout routed elsewhere | yes | **REMAP** — its history kept under the target's id |
| completed … | no | **HISTORY** — kept in the timeline, out of the flow |
| at a worker (ACTION, HTTP_CALL, LLM_CALL, RULE, USER_TASK, PROCESS), contract unchanged | yes | **KEEP_TASK** — the task stays where it is; the worker's answer lands; the target's timeout and retries apply |
| at a worker, contract changed | yes | **REDISPATCH** — the worker is told to stop (a PROCESS step's child is cancelled), and the step is dispatched again under the target; a late answer from the old attempt is dropped |
| waiting: a TIMER, a message or event wait, a reply wait, a retry backoff, a lock queue, a forEach's iterations | yes | **REARM** — its deadline and its message subscription recomputed from the target step |
| failed (ERROR, an unrouted TIMEOUT) | yes | **REMAP** — still failed: retry the process to run the target's step |
| active or failed | no | **refused** — map it |
| injected by a DYNAMIC step | — | **CARRY** — neither version knows it; it stays as it is |
| — a target step nothing maps onto | — | **ADD** — a new step, run when its preconditions hold |
| — the same, upstream of a step the process already reached | — | **ADD_PASSED** — recorded as passed, never run (it is not compensated either) |

A step's **contract** is what its worker is asked to do: its type, `topic`, `task`, `formId`, `ruleId`, `childWorkflowDefinitionId`, `http` and `llm`. A changed `timeout` or `retries` is not a contract change.

**Timers keep their start.** A re-armed TIMER counts from when it started: a longer duration extends it, a shorter one may make it due at once. A `until` computed from variables follows them as always.

**Messages are re-subscribed.** A wait whose message name or correlation key changed withdraws its old subscription and announces the new one (`MessageSubscriptionChanged`), so a sharded router routes the next message right.

**forEach.** A forEach running its iterations moves with them: each one at a worker is kept or dispatched again by the same contract rule, keeping its item. The target must be a forEach over the same `items`.

**Human tasks that escalated.** A USER_TASK waiting for someone keeps its task and the escalation levels that already fired. Levels are matched **by position**: level 1 of the old version is level 1 of the new one. The next escalation is re-armed from the new version's levels, counted from when the task was created — so a target that escalates sooner can make it due at once — and an `escalateTo` among the levels that fired stays the task's route. A target with fewer levels than have fired is refused. If the task's form changed it is re-dispatched as a new task, and its escalations start over. See [Human tasks](/guides/human-tasks-assignment-and-escalation/).

**Loops.** The current pass is migrated by its state; earlier passes are renamed with their step, so the loop's caps still count them.

**Locks.** A process inside a critical section (its LOCK completed, its UNLOCK not yet) or queued at a LOCK keeps its lock only if the LOCK step maps onto a LOCK of the same `lockName` and `lockKey`. A process that holds its definition's `processLock` is refused if the target declares another one; a target that adds one is taken at the next step.

**Compensation.** Refused while a saga rollback is under way and once any compensation has run. A completed compensable step with no counterpart becomes history, and a later rollback does not compensate it — the dry run warns about it.

**The process afterwards.** Its definition snapshot and version are the target's. A `RUNNING` process steps over at once (new steps may be ready); a `PAUSED` one stays paused, its re-armed timers frozen until it is resumed; an `ERROR` one stays failed — migrate, then retry. The migration writes `process-migrated` and a `ProcessStatusChanged` to the outbox with it, so the [process index](/guides/process-index/) shows the new version, and the step events of what it re-armed and re-dispatched — the same in every mode.

## Validation

Refused (errors):

1. The process is not `RUNNING`, `PAUSED` or `ERROR`.
2. A saga rollback is under way, or a compensation has already run.
3. The mapping names a step the process does not have, or one the target does not have; or maps two steps onto one.
4. A mapped pair of incompatible types. Worker tasks (ACTION, HTTP_CALL, LLM_CALL, RULE) can become one another; every other type only itself.
5. An active or failed step with no counterpart.
6. An active step whose preconditions in the target are not satisfied by what the process has completed — it would be running before its inputs.
7. An active forEach whose target is not a forEach over the same items.
8. A held or queued lock the target would not keep.
9. A human task that has fired more escalation levels than its target declares.
10. A variable a re-armed step reads right now (a timer's `untilVariable`, a correlation expression, a lock key) that the process does not have and `variableMapping` does not give.
11. An `AGENT` invocation in flight or under review: its outcome is the agent runtime's to give, and moving it would orphan it or dispatch it again. Wait for it, or cancel the process.

Warnings: variables read by a step ready to run that the process does not have, compensable work dropped, re-dispatched tasks, steps recorded as passed, a `processLock` the target adds or drops.

These rules live in the `definition-analysis` module (`MigrationRules`), so the engine and the Maven plugin apply exactly the same ones.

## Many processes: a MIGRATE batch

To move every live process of a version, use a [batch operation](/guides/searching-and-bulk-operations/#batch-operations) of type `MIGRATE` — from a definition's page (**Migrate instances…**), the Batch operations page, `POST /workflow/api/batch-operations` or the `migrateProcessesInBatch` MCP tool:

```http
POST /workflow/api/batch-operations
{"type": "MIGRATE", "filter": {"definitionId": "booking", "status": ["RUNNING", "PAUSED", "ERROR"]},
 "fromVersion": 3, "targetVersion": 5, "stepMapping": {"charge": "charge-card"},
 "reason": "v5 charges through the new gateway"}
```

The filter must name the definition. `fromVersion` limits it to the processes on that version — the others are skipped as not applicable. The same mapping applies to all; each process is planned on its own, and one its plan refuses is **skipped** (`notApplicable`), not failed, so a batch over a thousand processes moves the ones it can and tells you how many it could not. Try the dry run on one of them first.

## In CI: `check-migration`

Before a new version ships, the Maven plugin checks that the processes on the old one could be moved onto it — the "replay the history against the new code" of Temporal, for a DSL-first engine: it reads the two definitions, no history needed.

```bash
mvn eventconductor:check-migration -Dfrom=v3              # a git tag, against the working copy
mvn eventconductor:check-migration -Dfrom=v3 -Dto=v4
mvn eventconductor:check-migration -Dfrom=released/workflows -Dmapping=src/main/resources/migration.yaml
```

`from` and `to` are each `current` (the workflows directory as it is — the default for `to`), a path (a definition file or a directory of them) or a git ref, read with `git show` for every definition file. For each workflow in both, every step of the old version a process can be **waiting in** needs a compatible counterpart — by id, or by the mapping file:

```yaml
# migration.yaml — the same mapping you would give the migration
booking:
  charge: charge-card
```

A step without one fails the build (`-Deventconductor.migration.failOnError=false` only reports it). Instantaneous steps (START, END, FORK, JOIN, CHOICE, REPLY, SEND_MESSAGE, UNLOCK) and changed task contracts, forEach items, locks and fewer escalation levels are warnings. See the [Maven plugin reference](/reference/maven-plugin/#checking-a-migration-the-check-migration-goal).

## Sensitive variables

Migration follows the target's [sensitive variables](/guides/sensitive-data-and-encryption/):

* A variable the target declares sensitive and the source did not is **encrypted** by the migration — in the process row and in **every** step row of the process, history included; one the source declared and the target does not is stored **in clear** from then on. The dry run and `check-migration` report both as warnings (names only).
* A target that declares sensitive variables on an engine without encryption keys is refused (an error in the plan).
* Values the `variableMapping` sets are encrypted like any other write when the target declares them sensitive — also in the `MigrateProcessRequested` command on the broker and in a MIGRATE batch's stored parameters. The audit line names them and shows `••••`; the dry run never quotes a value.
* Rows written before the migration that the engine no longer rewrites — sent outbox rows, earlier log lines — are left as they were; the [outbox retention](/reference/configuration/) and the process retention bound how long.

## Audit, metrics and security

Every migration leaves a line in the process log — the **Operations** tab:

```
[migrate] by alice: migrated from v3 to v5; mapping [charge→charge-card]; redispatch [charge→charge-card]; add [notify] — reason: v5 charges through the new gateway
```

and counts on `eventconductor.processes.operations{operation="migrate"}`. It is authorized like every manual operation: the definition's flow authorization (`workflow.security.flow-authorization.enabled`) and `workflow.security.operations.required-roles`. All its timestamps are read through the engine clock, so the [test kit](/guides/testing-workflows/)'s time skipping applies.

## Not covered

- Moving a process to **another definition** (a different id).
- Transforming variables — set values with `variableMapping`, or use [set variables](/guides/operating-running-processes/#set-variables) before or after.
- Splitting one step into several, or merging several into one.
- Migrating the child processes of PROCESS steps along with their parent: each child is a process of its own, migrated on its own.
