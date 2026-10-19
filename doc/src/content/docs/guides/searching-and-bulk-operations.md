---
title: Searching and bulk operations
description: Search attributes — named, typed values every process is indexed by — and batch operations that cancel, retry, restart, pause or resume every process a filter matches.
---

Two questions an operator asks every day: *which processes are about customer C-9?* and *retry every one that failed during last night's outage*. EventConductor answers the first with **search attributes** — values a definition declares and every process of it is indexed by — and the second with **batch operations** — one command applied, in the background, to every process a filter matches. They are the engine's counterparts of Temporal's Search Attributes and List Filter, and of Camunda's batch operations, declared in the definition rather than registered on a cluster.

## Search attributes

A definition lists them under `searchAttributes`, name → expression:

```yaml
id: order-fulfilment
name: Order fulfilment
searchAttributes:
  customerId: "${customerId}"                         # shorthand: a keyword
  amount:   { value: "${total}", type: number }
  placedOn: { value: "${placedOn}", type: date }
  region:   { value: "${address.region}" }            # type defaults to keyword
  note:     { value: "order ${businessKey}: ${comment}", type: text }
  vip:      { value: "${total > 1000}", type: bool }
steps: [...]
```

The value is a [payload template](/guides/payload-templates/): `${…}` JEXL expressions over the process variables (and `process`, `businessKey`), in the same sandbox as every other expression of a definition. A string that is exactly one expression keeps its type; text around expressions makes a string.

| Type | Holds | A filter matches |
|---|---|---|
| `keyword` (default) | text, up to 255 characters | exactly (`customerId = C-9`) |
| `text` | text, up to 255 characters | case-insensitively, as a substring (`note = rush`) |
| `number` | a decimal number | equal, or a range (`amount > 100`) |
| `date` | an ISO date or date-time (an offset or `Z` is converted to the engine's time zone) | equal, or a range (`placedOn <= 2026-10-18` — a bare date as an upper bound is its last moment) |
| `bool` | `true` / `false` | exactly |

Rules — the same in the engine, the [Maven plugin](/reference/maven-plugin/) and the IDE plugins' schema: at most **20** attributes; a name is a letter followed by letters, digits and `_` (at most 64 characters), each once regardless of case; a value is a template with at least one `${…}` (a constant would be the same for every process), at most 500 characters; the type is one of the five above. A definition that breaks them is refused at load, like any other invalid definition.

**When they are computed.** Whenever the process is saved — at its start, at every variable change, at every status change, at its end — at the one point every change goes through (`ProcessRepository.save`). They are recomputed from the variables and written only when they changed. An expression that fails, an empty value, or a value that is not of its type (`"12a"` for a number) is simply not indexed: a search attribute is a way to find a process, never a reason for one to fail. A process of a definition that declares none costs nothing.

**Part of the version.** Changing `searchAttributes` mints a new definition version. A process evaluates the attributes of the version it runs (they travel in its definition snapshot), so the definition shown as v3 is exactly what v3's processes can be found by; processes of v2 keep v2's attributes.

**Per process, not per iteration.** A forEach iteration's item (`as`) is laid over the variables for that iteration only; attributes are computed from the process's own variables, so an iteration never changes them — what the loop leaves on the process (its `output`) does.

**Where they are kept.**

- **`jpa`** — `process_search_attribute` (migration V48): one row per process and attribute, the value in the column its type uses, indexed by (name, value). The [retention purge](/guides/workflow-definitions/#process-retention) deletes the rows with their process.
- **[Process index](/guides/process-index/)** (`workflow.projection.enabled`) — the attributes ride on `ProcessStatusChanged`, which is now also emitted when only the attributes changed, into `process_index_search_attribute` (the read database's V5, the write database's V48). A listing narrowed only by text and attributes is answered from the index, which has seen every shard.
- **`memory`** — nothing is stored: the listing computes each process's attributes when it asks.

### Searching

Every door that lists processes takes the same conditions, ANDed with each other and with the other filters (definition, tag, status, dates, business key, text):

- **Processes page** — the *Search attributes* filter takes the query language below (`customerId = "C-9" AND amount > 100`). It can be bookmarked: `/workflow/processes?attributes=customerId%20%3D%20C-9`.
- **REST** — [`GET /workflow/api/processes`](/reference/processes-rest-api/#listing): `attr.customerId=C-9`, `attr.amount.gte=100`, `attr.placedOn.lt=2026-11-01` (`gt`, `gte`, `lt`, `lte`), repeatable; and `query=` for the same conditions as text. `GET /workflow/api/processes/{id}` shows a process's `searchAttributes`.
- **MCP** — `listProcesses` takes `workflowDefinitionId`, `status`, `tag` and `query`.

The **query language** is deliberately small: conditions `name op value` joined by `AND` (case-insensitive); operators `=`, `>`, `>=`, `<`, `<=`; values bare (no spaces) or quoted with `"` or `'` (`\` escapes the quote). No `OR`, parentheses or negation. It is parsed into typed conditions and every value is bound as a query parameter — it never reaches a database as text. At most 10 conditions per listing; a malformed one is a `400`, never "no filter".

A filter does not need to know an attribute's type — two definitions may declare `amount` differently: `=` compares with a keyword exactly, a text as a substring, and a number or a date when the value reads as one; a range compares numbers and dates only, and is refused for a value that is neither.

## Batch operations

A batch operation applies one command — `CANCEL`, `RETRY`, `RESTART`, `PAUSE`, `RESUME`, or one of the [manual operations](/guides/operating-running-processes/) `SET_VARIABLES` (variables to set and remove), `RETRY_FROM_STEP` (a step id) and `MIGRATE` (a target version, a `fromVersion`, a step mapping and variables — see [Migrating running processes](/guides/migrating-running-processes/#many-processes-a-migrate-batch)), which take a mandatory `reason` — to every process a filter matches. The filter is the listing's (definition, tag, statuses, creation dates, business key, text, only-errors, search attributes) and must narrow something: a batch never acts on every process by default.

```bash
curl -X POST https://engine.example.com/workflow/api/batch-operations \
  -H 'X-Api-Key: …' -H 'Content-Type: application/json' \
  -d '{"type": "RETRY", "filter": {"definitionId": "order-fulfilment", "status": ["ERROR"],
                                   "from": "2026-10-18T02:00", "query": "region = EU"}}'
```

It answers `202` with the batch (`CREATED`, and how many processes the filter matched), which a background runner then walks:

- **A page at a time, by process id.** The runner reads the next `page-size` matching processes after the last id it reached, applies the command to each, and saves the cursor and the counters. Walking by id, not by page number, means processes leaving the filter as the batch acts on them (a cancelled process is no longer `RUNNING`) never shift the pages under it.
- **Each process is one ordinary command** — exactly what `POST /workflow/api/processes/{id}/cancel` (or `/variables`, `/retry-from/{stepId}`) does; the manual operations are audited in each process with the batch's creator as the operator and the batch's reason and id: the definition's [flow authorization](/reference/configuration/#core-properties) is checked against the caller who created the batch, the use case's own status rule decides whether it applies, and the use case runs on the node that owns the process, under its lock.
- **Counted.** *Succeeded*: the command was accepted. *Skipped*, by reason: `notApplicable` (the process is in a status the command does not apply to — retrying a `RUNNING` one), `notAuthorized` (the caller may not operate that definition), `gone` (purged since it matched). *Failed*: anything else, with a sample of the errors (the first 20).
- **Incidents.** An incident's *Retry all* is a `RETRY` batch over the incident: the ERROR processes of its definition whose failure is one of the group's, each with its failed steps retried and audited.
- **Throttled** to `max-per-second` processes a second (real-time pacing, not business time), so a batch over a hundred thousand processes does not crowd out the engine's own work.
- **One node at a time.** On a database the runner works under an advisory lock, like the retention purge; in memory, in-process. It is a scheduler pass: with `workflow.schedulers.background=false` the [test kit](/guides/testing-workflows/) drives it.
- **Resumable.** A node that dies mid-batch loses at most the page in hand; the next one to take the lock carries on after the cursor. A command repeated on a process it already moved is refused by its status rule and counted as skipped.
- **Cancellable.** `POST /workflow/api/batch-operations/{id}/cancel` — or *Cancel batch* in the console, or `cancelBatchOperation` — stops it between two processes. What was done stays done.

Statuses: `CREATED` → `RUNNING` → `COMPLETED` (every matching process visited, whatever each outcome), `FAILED` (the batch itself could not go on — its filter no longer reads), or `CANCELLED`. The number *matched* is an estimate while it runs (processes can enter and leave the filter) and becomes the number visited once it completes.

**Where to start one.**

- **Console** — *Workflow → Batch operations → New* takes the operation and the Processes page's filters; the Processes page's *Retry all matching* and *Cancel all matching* turn the filter on screen into a batch. A batch's page shows a progress bar, the counters, the skip reasons and the errors.
- **REST** — `POST/GET /workflow/api/batch-operations`, `GET /{id}`, `POST /{id}/cancel` ([reference](/reference/processes-rest-api/#batch-operations)).
- **MCP** — `createBatchOperation`, `listBatchOperations`, `getBatchOperation`, `cancelBatchOperation`.

Batches are kept in `batch_operation` (migration V49) on a database, in the heap in memory mode. The runner is configured under [`workflow.batch-operations.*`](/reference/configuration/#batch-operations-workflowbatch-operations) and observed through [`eventconductor.batch.operations`](/reference/observability/).

:::note[Sharded fleets]
In a sharded deployment a batch acts on the processes of the shard that runs it: the walk reads that shard's own store. Searching by attributes across the fleet goes through the process index, as above.
:::
