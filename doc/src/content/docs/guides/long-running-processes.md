---
title: Long-running processes
description: CONTINUE_AS_NEW keeps the history of entity- and subscription-style processes bounded by handing over to a fresh run under the same business key; businessKeyPolicy decides whether a business key may name more than one run.
---

A process that lives for months — a subscription, an account, a device — accumulates history: every pass of a
loop is a step execution, every month a few more rows. Two features (2.33) keep such processes healthy:

- **`CONTINUE_AS_NEW`** — end this run and start a fresh one of the same definition, same business key, with
  only the variables that matter. History is bounded by run.
- **`businessKeyPolicy`** — whether a business key may name more than one run over time, and what a start does
  while one is running.

They are Temporal's Continue-As-New and `WorkflowIdReusePolicy`, declared in the definition.

## `CONTINUE_AS_NEW`

```yaml
id: subscription
businessKeyPolicy: unique
steps:
  - { id: start, type: START, name: Start }
  - { id: bill, type: ACTION, name: Bill the month, topic: billing, preconditionStepId: start }
  - id: next-month
    type: CONTINUE_AS_NEW
    name: Next month
    preconditions: [ { stepId: bill, expression: "active == 'true'" } ]
    continueAsNew:
      version: latest                 # same (default) | latest
      variables:                      # absent: every variable; a list: these names; a map: name → template
        customerId: "${customerId}"
        month: "${month + 1}"
        active: "${active}"
  - { id: done, type: END, name: Done, preconditions: [ { stepId: bill, expression: "active != 'true'" } ] }
```

When a `CONTINUE_AS_NEW` step is reached, in one transaction:

1. the variables are computed from the process as it stands — all of them, the listed ones that are set, or
   each template rendered over the process (a template that fails is logged and its variable not carried: the
   continuation is never lost over one value);
2. **this run ends as an `END` does**: `COMPLETED`, with outcome `continued`; any other branch still in flight
   is cancelled;
3. a **new run** is created: the same definition — the snapshot this run ran (`version: same`, the default) or
   the definition as the engine has it now (`latest`) — the same business key, the carried variables.

The two are linked: the old run's `continuedAs` is the new run's id, the new run's `continuedFrom` the old
one's. The REST [process view](/reference/processes-rest-api/) carries `continuedFrom`, `continuedAs` and
`outcome: "continued"`; the console and MCP show the links. `GET …/processes?businessKey=` lists every run of
the chain; looking a process up **by business key** answers the run that holds the key now — the latest.

Rules (engine and Maven plugin): no step may follow a `CONTINUE_AS_NEW` (it ends its run); it cannot carry
`forEach`, `repeat` or `loopBack`; `version` is `same` or `latest`; `variables` is a list of names or a map of
templates. A sync-invoked process that continues as new without having replied is answered as one that
completed without a reply.

### Children

A child process (started by a `PROCESS` step) that continues as new hands its `parentStepExecutionId` to the
next run: the parent's step stays waiting and is completed when the **last** run of the chain ends, with that
run's variables.

### Bounded history with loops

Combine it with [loops](/guides/loops-and-multi-instance/): loop through the month's events with `loopBack`, and
after N passes (or at a month boundary) `CONTINUE_AS_NEW`. Each run holds at most N passes; the chain holds
the rest. [Retention](/guides/workflow-definitions/#process-retention) judges each run on its own end: finished
runs of a chain are purged while the current one carries on, and a purged predecessor leaves its id behind in
`continuedFrom`.

## `businessKeyPolicy`

```yaml
businessKeyPolicy: allowAfterFinished   # unique (default) | allowAfterFinished | allowAfterFailed | terminateRunning
```

| Policy | When a start finds the key held by a run of this definition that is… |
|---|---|
| `unique` (default) | anything: the start collapses into it — the behaviour before 2.33 |
| `allowAfterFinished` | ended (`COMPLETED`, `ERROR`, `CANCELLED`, `COMPENSATED`, `COMPENSATION_FAILED`): a new run takes the key; running: collapses |
| `allowAfterFailed` | ended other than `COMPLETED`: a new run takes the key; completed or running: collapses |
| `terminateRunning` | running: it is cancelled (compensation runs as for any cancel), then a new run takes the key; ended: a new run takes the key |

The policy is applied where every start ends — process creation — so it holds for every door: REST, sync
invocation, event triggers, cron, the console, upstream records. A key held by **another definition's**
process is never reused (409 on REST, an `ERROR` receipt for a trigger, as before). `CONTINUE_AS_NEW` always
reuses its own key, whatever the policy.

### Storage

`process_entity.business_key` **stays `UNIQUE`**: it names the run that holds a key *now*. A run that hands its key
over — the run a `CONTINUE_AS_NEW` ended, or the run a reuse policy lets a new start replace — moves the key to
`released_business_key` (V56) in the same transaction, before the new run is inserted. The engine writes that
release at once (a flush orders inserts first), and it goes through the run's optimistic version, so two starts
racing to take one key over cannot both win: the loser rolls back and is redelivered. Lookups by business key
(`findByBusinessKey`, REST `GET …?businessKey`, MCP `findProcessByBusinessKey`) answer the holder; the listing and
MCP `listRunsOfBusinessKey` show every run under the key. Purging the holder frees the key.

### Upgrading to 2.33 (rolling)

Rolling upgrades are safe: V56 only adds tables and nullable (or constant-default) columns, and keeps every
constraint an older engine relies on. During the rollout:

- A 2.32 engine still finds the one holder of a key in `business_key`, and its duplicate creations still fail on the
  `UNIQUE` constraint — whichever engine version creates, there is never more than one process holding a key.
  (Tested on PostgreSQL: a 2.32-style insert racing a 2.33 hand-over never yields two running processes.)
- A run a 2.33 engine has continued or replaced shows an empty business key in a 2.32 console until that pod is
  upgraded — cosmetic.
- A 2.32 engine does not know `businessKeyPolicy`, `startPolicy` or `CONTINUE_AS_NEW`: a start it handles collapses
  into the key's holder as before, a trigger it receives starts without flow control, and a process definition
  using `CONTINUE_AS_NEW` must not be run by a 2.32 pod. Import such definitions once every pod runs 2.33.
- **Large tables**: the new columns need no table rewrite on PostgreSQL 11+, but each `ALTER TABLE` takes a brief
  exclusive lock — run the upgrade outside peak time, or set a `lock_timeout` for the migrating connection. The two
  new indexes on existing tables (`process_entity.released_business_key`, `http_task (topic, priority, created_at)`)
  are built `CONCURRENTLY` on PostgreSQL by V56.1 (writes carry on); on other databases with a plain
  `CREATE INDEX`.

### Interplay

- **Idempotent REST starts** — `POST /workflow/api/processes` answers 200 with the existing process while the
  policy keeps it; once the policy lets the key be reused, the same request starts a new run (201). A client
  that retries blindly after the first run ended therefore gets a **second run** under a reuse policy: keep
  `unique` for such clients, or use synchronous invocation's `Idempotency-Key`.
- **Event triggers** — a trigger's default business key is derived from the event, so it is different for every
  event and the policy only matters with a `businessKey` template (`order-${data.orderId}`). Redeliveries are
  still collapsed by the [receipts](/guides/domain-event-triggers/#each-event-is-used-once).
- **Synchronous invocation** — the `Idempotency-Key` still answers the same invocation; `BUSINESS_KEY_TAKEN` is
  raised only when the policy refuses the key.
- **Flow control** — a [singleton](/guides/flow-control/) is per *flow-control key*, the policy per *business
  key*; `singleton: replace` with a per-entity key and `terminateRunning` with a per-entity business key say
  much the same thing at different doors.

## Compared with Temporal

| EventConductor | Temporal |
|---|---|
| `CONTINUE_AS_NEW` step, `continueAsNew.version: same \| latest` | `ContinueAsNew` (same or new workflow type/version) |
| `continueAsNew.variables` | the new run's input arguments |
| `continuedFrom` / `continuedAs`, same business key | same workflow id, new run id, chained |
| `businessKeyPolicy: unique` | `WorkflowIdReusePolicy: REJECT_DUPLICATE` |
| `allowAfterFinished` | `ALLOW_DUPLICATE` |
| `allowAfterFailed` | `ALLOW_DUPLICATE_FAILED_ONLY` |
| `terminateRunning` | `TERMINATE_IF_RUNNING` / conflict policy `TERMINATE_EXISTING` |
