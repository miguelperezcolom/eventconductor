---
title: Operating running processes
description: Change a live process by hand — set its variables, complete a step, run it again from a step — and work through incidents, with every operation authorized and audited.
---

Retry, restart, pause, resume and cancel act on a process as a whole. Sometimes an operator needs to reach inside one: a worker wrote a wrong value, a payment was taken by phone so the step that charges the card must not run, a downstream system was fixed and the run should resume a few steps back. EventConductor has three **manual operations** for that — the instance modification of Camunda Operate, the reset of Temporal — and an **Incidents** view that groups the failures waiting for someone.

| Operation | What it does | Allowed on |
|---|---|---|
| **Set variables** | Writes `set`, deletes `remove` | a `RUNNING`, `PAUSED` or `ERROR` process |
| **Complete step** | Marks one step execution `COMPLETED` with the output variables you give, as if its worker had answered, and lets the flow carry on | a `PENDING`, `RUNNING`, `AWAITING_RETRY`, `ERROR` or `TIMEOUT` step of a `RUNNING`, `PAUSED` or `ERROR` process |
| **Retry from step** | Reopens a step and every step downstream of it; the flow runs again from there | a step the process has already reached, of a `RUNNING`, `PAUSED` or `ERROR` process |

To move a process onto another version of its definition instead, see [Migrating running processes](/guides/migrating-running-processes/).

All three are reached from the process detail page (**Set variables**, **Complete step**, **Retry from step** — each opens a dialog that asks for the reason), the [processes REST API](/reference/processes-rest-api/#manual-operations) and the [MCP tools](/guides/mcp-overview/). A **reason is mandatory**.

## How an operation runs

Like the other operator commands, the door that takes the request — UI, REST, MCP — does not carry it out. It checks what it can answer at once (the process exists, its status and the step's allow the operation, the caller may do it) and publishes a request event (`set-process-variables-requested`, `complete-step-manually-requested`, `retry-from-step-requested` on `upstream`). The node that owns the process carries it out, **under the process lock**, reading the process again and checking again — it may have moved on in between. A request that is refused at that point is not lost silently: the refusal is written to the process log with the reason.

The operations emit the normal domain events, so step-over, the [process index](/guides/process-index/) and the analytics read model stay consistent in every mode (embedded + memory, embedded + JPA, Kafka). A request delivered twice is harmless: writing a value that is already there changes nothing, completing a step that is already `COMPLETED` does nothing, and a retry from a step simply reopens it again.

## Set variables

```http
POST /workflow/api/processes/{id}/variables
{"set": {"checkIn": "2026-11-02"}, "remove": ["tempQuote"], "reason": "Guest moved the stay by phone"}
```

What the variables feed follows them, exactly as when a worker writes them: a `WAIT_FOR_MESSAGE` step's correlation key, and a `TIMER` or `deadline` computed from a variable (`until: checkIn - 3d`), are re-armed. On a `RUNNING` process, a step held by a [link guard](/guides/workflow-definitions/) that now holds is released. A `PAUSED` process keeps its successors held until it is resumed; an `ERROR` process stays failed — fix the data, then retry.

## Complete step

```http
POST /workflow/api/processes/{id}/steps/{stepExecutionId}/complete
{"variables": {"paymentRef": "PHONE-8812"}, "reason": "Paid by phone"}
```

- A step still at a worker is told to stop (`TaskCancellationRequested`), as a cancel does. If the worker answers anyway, the answer is dropped: the step is terminal. A `PROCESS` step's running child process is cancelled.
- The output variables are written onto the process like a worker's reply.
- The step goes to `COMPLETED` through the ordinary status pipeline and its successors start. A failed process whose **last** blocking failure this was goes back to `RUNNING` — `finished` is cleared, so the analytics retract its failure; with another failure still standing it stays `ERROR`.
- `LOCK` and `UNLOCK` steps are refused (completing a `LOCK` without the lock would enter a critical section unprotected), and so are compensation steps, which only the saga rollback runs.

**Compensation.** A step completed by hand *is* completed: if the saga fails later, its compensation runs like any other completed step's. That is what saying it completed means.

## Retry from step

```http
POST /workflow/api/processes/{id}/retry-from/{stepId}
{"reason": "Partner API fixed"}
```

`stepId` is the definition's step id. That step and, transitively, every step that depends on it go back to the state they were created in: steps whose preconditions name it (links, a `JOIN`'s branches, a `CHOICE`'s outgoing edges) and its on-timeout route. Everything else — upstream steps, parallel branches that do not depend on it — is left exactly as it is.

- **JOIN.** A `JOIN` downstream waits again for the reopened branch; its other branches stay completed and are not re-run.
- **CHOICE.** A `CHOICE` downstream decides again, from the variables as they are now: its latch is released together with its branches. To change the route, set the variable, then retry from a step at or before the `CHOICE`. Retrying from one branch of a `CHOICE` re-runs that branch only if the `CHOICE` would still pick it.
- **Late replies.** Each reopened step gets a new dispatch number (the attempt number every task carries), so a worker still answering for the abandoned attempt is ignored rather than taken for the re-run's result. A step still at a worker is told to stop; a `PROCESS` step's running child is cancelled.
- **The process.** A failed process goes back to `RUNNING` with `finished` cleared, as with retry and restart. A `PAUSED` process stays paused; the reopened steps wait for the resume.

It is refused when:

- the step has not run yet — there is nothing to run again from it;
- another failed step is **not** downstream of it: the process would fail again at once. Retry from a step both depend on, or deal with the other failure first;
- a step to reopen is queued for a lock (`WAITING_ON_LOCK`) on a live process;
- the step is a compensation.

### Locks

The definition's `processLock` is taken again the way a [restart](/guides/starting-a-process/) takes it: a failed process gave it up when it failed, and as soon as the process is `RUNNING` again the step-over gate re-acquires it — or queues the process until it is free.

A step-level critical section (`LOCK` … `UNLOCK`) is different, because the `LOCK` step that opened it is upstream and is not re-run. When the reopened steps include work inside a section the process no longer holds (its `UNLOCK` is reopened too, or there is none), the lock is taken again, without queueing. If another process holds it by now, the retry point **moves back to the section's `LOCK` step**, which re-runs and queues for the lock like any `LOCK`. The audit line says which happened. The re-run never enters a critical section without its lock.

## Loops and forEach

The operations see a process the way the orchestrator runs it: one **current** execution per step. A loop's earlier passes (`repeat`, `loopBack`) are history, and a forEach is one step whose iterations its container drives.

| Target | Complete step | Retry from step |
|---|---|---|
| A loop step's **current pass** | Completes it as if its worker had answered; its `until`/`while` is then decided | Reopens it (and what follows); it runs again as the same pass and decides again — no new pass is added |
| An **earlier, superseded pass** | Refused: it is history | Not a target: a retry is planned over current executions |
| A forEach **iteration** | Allowed while its container is still running it. The outputs are recorded on the iteration, never on the process, and the container collects them into its `output` | Not a target — retry from the forEach step |
| A forEach **container** (the step) | Stops its iterations (those in flight are told to), completes the step; the outputs go onto the process. Its `output` collection is not produced — pass it as an output variable if what follows needs it | Discards its iterations (those in flight told to stop) and evaluates `items` afresh when it runs again. To re-run only the failed iterations, retry the step instead |

An iteration of a forEach that has already failed is refused ("complete or retry the forEach step itself"). Earlier passes kept as history still count against the loop's caps (`maxIterations`, `maxSuccessfulExecutions`); restart the process to start a loop's count over. Incidents list the current executions too: a failed forEach is one incident on its step, not one per iteration.

## Compensations and terminal processes

None of the operations applies to a finished process: `COMPLETED`, `CANCELLED` (use retry or restart, which revive what the cancel stopped), `COMPENSATED` and `COMPENSATION_FAILED`. A failed process whose **saga rollback is under way** — a compensation is about to run or is running — is refused for complete-step and retry-from-step: the rollback is undoing exactly what they would build on. Once it ends the process is `COMPENSATED` or `COMPENSATION_FAILED`, finished either way. Setting variables is allowed during a rollback; it moves no step.

## Incidents

An **incident** is a failure an operator can act on: a step execution in `ERROR` (or in a `TIMEOUT` with no on-timeout route) of a process that is `ERROR`. The **Incidents** page groups them by **workflow, step and error family**, most failures first, with how many steps and processes each group holds and when it was first and last seen.

The error family is the step's last error line with what varies between occurrences taken out — ids and UUIDs become `<id>`, numbers `#`, quoted values `'…'` — so *Payment 4711 declined for 'ACME'* and *Payment 4712 declined for 'Initech'* are one incident, while a timeout of the same step is another. Opening a group lists its processes (each a link to the process) and offers **Retry all**, which asks for a reason and retries every failed step of the group, each exactly like a step retry from the console and each recorded in its process's audit trail.

```http
GET  /workflow/api/incidents?definitionId=orders
GET  /workflow/api/incidents/{key}/processes
POST /workflow/api/incidents/{key}/retry   {"reason": "Gateway back up"}
```

The grouping reads at most `workflow.incidents.scan-limit` failed steps (default 10000, the most recent first) and says when it stopped there (`truncated`). A group retry is a `RETRY` [batch operation](/guides/searching-and-bulk-operations/#batch-operations) over the incident (2.30): it answers at once with the batch's id, and the batch runner retries each member process's failed steps of the incident in the background — throttled, one node at a time, resumable and cancellable, each recorded in the process's audit trail with the operator who asked and the reason (followed by the batch id). The UI's *Retry all*, `POST /workflow/api/incidents/{key}/retry` (whose `Location` names the batch) and the `retryIncident` MCP tool all create it; follow it on the Batch operations page. On JPA the query is two indexed reads — failed processes joined to their failed steps, then the error lines of just those processes — in plain JPQL, the same on PostgreSQL, H2, MariaDB and Oracle; the grouping into families happens in the engine, identically for every store.

## Audit

Every manual operation leaves a line in the process's own log:

```
[retry-from-step] by alice: ran the process again from step b; reopened b (was COMPLETED), c (was ERROR); the process is running again — reason: Partner API fixed
```

- **Who** — the line's `workerId` is `operator:<subject>`, the caller as identified at the door (`operator:anonymous` when nobody was);
- **when** — its timestamp;
- **what changed** — old and new values for variables, the step and its previous status, the reopened steps, the locks re-taken;
- **why** — the reason.

The process detail page lists these lines in its **Operations** tab; they also come back from `GET /workflow/api/processes/{id}/logs` and the `getProcessLogs` MCP tool. Each operation counts on the `eventconductor.processes.operations` counter, tagged `operation` (`set-variables`, `complete-step`, `retry-from-step`, `retry-incident`) and `workflowDefinitionId` — see [Observability](/reference/observability/).

## Authorization

These are admin operations. Two gates apply, at every door (UI, REST, MCP, the incident retry):

1. With `workflow.security.flow-authorization.enabled=true`, the definition's `requiredScopes` / `requiredRoles` — as for every other command on its processes.
2. `workflow.security.operations.required-roles` — roles the caller must **all** hold for any manual operation, whether or not flow authorization is on. Empty by default: the manual operations are then gated exactly like the other commands.

```properties
workflow.security.operations.required-roles=workflow-admin
```

A caller who lacks something gets `403` naming it (the UI and MCP say it). The caller is resolved by the same `CallerResolver` as everywhere else — see [Configuration](/reference/configuration/).

## Over many processes at once

Changing variables and retrying from a step are also [batch operation](/guides/searching-and-bulk-operations/#batch-operations) types: `SET_VARIABLES` (variables to set and to remove) and `RETRY_FROM_STEP` (a step id) apply the operation to every process a filter matches, each through the same use case, authorization and audit as a single one — the batch's creator is the operator each audit line names, with the batch's reason (mandatory) and its id.
