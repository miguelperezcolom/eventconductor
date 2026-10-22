---
title: Processes REST API
description: Start, read, list, cancel, pause, resume, retry and restart processes over HTTP — /workflow/api/processes — with the OpenAPI description.
---

`/workflow/api/processes` is the engine's REST API for processes: start one, read it, list them, and send it the operator commands — the same operations the admin console and the [MCP tools](/guides/mcp-overview/) offer. It is built on the same application services, so a start over HTTP is the console's "new process" (same idempotency, same disabled-definition rule, same [flow authorization](/reference/configuration/#core-properties)), a listing is the console's listing, and a command is the same request the console and MCP send to the node that owns the process.

The machine-readable description is published with these docs: **[`/openapi/eventconductor-api.yaml`](/openapi/eventconductor-api.yaml)** (OpenAPI 3.1, every `/workflow/api/**` endpoint). A build-time test regenerates it from the controllers and fails when the committed copy drifts, so it is the API the release actually serves.

## Endpoints

| Method and path | Does | Answers |
|---|---|---|
| `POST /workflow/api/processes` | Start `{definitionId, businessKey?, variables?}` | `201` + `Location`; `200` when the business key already names a process of that definition (and its `businessKeyPolicy` keeps it), or a `startPolicy` singleton skipped the start; `202` `StartHeldBack` when a `startPolicy` debounced or queued it; `429` + `Retry-After` when its throttle dropped it |
| `GET /workflow/api/processes` | One page of the listing (filters below) | `200` `ProcessPage` |
| `GET /workflow/api/processes/by-key?businessKey=…` | The process with that business key (exact) | `200` `Process` |
| `GET /workflow/api/processes/{id}` | The process, with its variables | `200` `Process` |
| `GET /workflow/api/processes/{id}/steps` | Its step executions, in creation order | `200` `StepExecution[]` |
| `GET /workflow/api/processes/{id}/variables` | Its variables, by name (a [sensitive](/guides/sensitive-data-and-encryption/) one reads `••••`) | `200` `{name: value}` |
| `POST /workflow/api/processes/{id}/variables/{name}/reveal` | One sensitive variable's value `{reason}` — needs a `workflow.security.sensitive.reveal-roles` role; audited | `200` `RevealedVariable`; `403` without the role |
| `GET /workflow/api/processes/{id}/logs` | Its log, oldest first | `200` `LogEntry[]` |
| `POST /workflow/api/processes/{id}/cancel` | Cancel (not a `COMPLETED` process) | `202` |
| `POST /workflow/api/processes/{id}/pause` | Pause a `PENDING` or `RUNNING` process | `202` |
| `POST /workflow/api/processes/{id}/resume` | Resume a `PAUSED` process | `202` |
| `POST /workflow/api/processes/{id}/retry` | Pick an `ERROR` or `CANCELLED` process up where it stopped | `202` |
| `POST /workflow/api/processes/{id}/restart` | Run an `ERROR` or `CANCELLED` process again from the beginning | `202` |
| `POST /workflow/api/processes/{id}/steps/{stepExecutionId}/retry` | Run one `ERROR`/`TIMEOUT` step execution again | `202` |
| `POST /workflow/api/processes/{id}/variables` | Change a live process's variables `{set?, remove?, reason}` | `202` |
| `POST /workflow/api/processes/{id}/steps/{stepExecutionId}/complete` | Complete a step execution by hand `{variables?, reason}` | `202` |
| `POST /workflow/api/processes/{id}/retry-from/{stepId}` | Run a live process again from one of its steps `{reason}` | `202` |
| `POST /workflow/api/processes/{id}/migrate` | Move a live process onto another version of its definition `{targetVersion?, stepMapping?, variableMapping?, reason}` | `202` |
| `POST /workflow/api/processes/{id}/migrate/dry-run` | The plan of that migration, changing nothing `{targetVersion?, stepMapping?, variableMapping?}` | `200` `MigrationPlan` |
| `POST /workflow/api/processes/{id}/updates/{name}` | Apply a declared [update](/guides/updating-running-processes/); body = its payload; `Idempotency-Key`, `Prefer: wait=N` | `200` `ProcessUpdateResponse` with `result`; `202` + `Location` past the deadline |
| `POST /workflow/api/processes/by-key/updates/{name}?businessKey=…` | The same, finding the process by business key | as above |
| `POST /workflow/api/definitions/{definitionId}/update-with-start` | Update the process with `businessKey`, starting it when there is none `{businessKey, variables?, update, payload?}` | as above, with `started` |
| `GET /workflow/api/updates/{updateId}` | One update, answered as its POST was (`Prefer: wait=N` long-polls) | `200` / `202` / its problem |
| `GET /workflow/api/processes/{id}/updates` | A process's updates, oldest first | `200` `ProcessUpdateResponse[]` |
| `GET /workflow/api/incidents?definitionId=…` | Failed steps of failed processes, grouped by definition, step and error family | `200` `IncidentList` |
| `GET /workflow/api/incidents/{key}/processes?limit=…` | The failed steps (and processes) of one incident | `200` `IncidentMember[]` |
| `POST /workflow/api/incidents/{key}/retry` | Retry every failed step of one incident `{reason}` | `202` `IncidentRetryAccepted` |

A step that ran more than once has a row per run in `/steps`: a [forEach](/guides/loops-and-multi-instance/) iteration carries `parentExecutionId` (its container's id) and `itemIndex`; a loop pass carries `iteration` (0 for the first run) and `superseded: true` once a later pass replaced it. Retrying an iteration retries its forEach, which re-runs only the failed and cancelled items.

What retry, restart, pause and resume do is described in [Starting a process](/guides/starting-a-process/#pausing-and-resuming); the statuses in [Process & Step Statuses](/reference/statuses/).

### Starting

```bash
curl -X POST https://engine.example.com/workflow/api/processes \
  -H 'X-Api-Key: …' -H 'Content-Type: application/json' \
  -d '{"definitionId": "order-fulfilment", "businessKey": "ORDER-1001",
       "variables": {"orderId": "1001", "lines": [{"sku": "A", "qty": 2}]}}'
```

```http
HTTP/1.1 201 Created
Location: /workflow/api/processes/5f0c…

{"id": "5f0c…", "definitionId": "order-fulfilment", "definitionVersion": 3, "name": "Order fulfilment",
 "businessKey": "ORDER-1001", "status": "PENDING", "completionPercentage": 0,
 "created": "2026-10-18T09:30:00.123", "started": null, "finished": null, "pausedAt": null,
 "parentStepExecutionId": null, "variables": {"orderId": "1001", "lines": "[{\"sku\":\"A\",\"qty\":2}]"}}
```

- **Idempotent on the business key.** The same `businessKey` and `definitionId` again answers `200` with the process that already has it — a retried request never starts a second process. The same key for *another* definition is `409`. Without a business key every request starts a new process. A definition's [`businessKeyPolicy`](/guides/long-running-processes/#businesskeypolicy) can let a new run take the key once the previous one ended (or cancel a running one): then the same request starts a new run (`201`).
- **Flow control.** A definition's [`startPolicy`](/guides/flow-control/) decides first: the `EventConductor-Flow-Control` header says what it did (`started`, `replaced`, `skipped`); a held-back start answers `202` with `{flowControl, businessKey, dueAt, detail}` and no process yet; a dropped one `429` with `Retry-After`.
- **Chains.** A process view carries `continuedFrom`, `continuedAs` and `outcome` (`continued` for a run a [`CONTINUE_AS_NEW`](/guides/long-running-processes/) handed over); `by-key` answers the run that holds the key now.
- **Variables are strings**, as the engine keeps them: a string stays itself, any other JSON value is stored as its JSON text.
- A `traceparent` (and optionally `tracestate`, `baggage`) header makes the process join the caller's trace (`workflow.tracing.join-caller-trace`).
- A paused definition still accepts the start: the process is created `PAUSED`. A disabled or archived one does not: `409`.
- To start a process and **wait for its answer**, use [synchronous invocation](/guides/synchronous-invocation/) instead.

### Listing

`GET /workflow/api/processes` answers one page of processes, newest first, narrowed by every filter given (they combine with AND):

| Parameter | Narrows to |
|---|---|
| `definitionId` | processes of this definition |
| `tag` | processes of the definitions carrying this [tag](/guides/workflow-definitions/) (case-insensitive); a tag no definition carries lists nothing |
| `status` | any of these statuses — repeat it (`status=ERROR&status=CANCELLED`) or separate with commas; an unknown status is `400` |
| `from`, `to` | created at or after / at or before — an ISO date-time (`2026-10-18T09:30:00`) or a date (`from` = its first moment, `to` = its last), in the engine's time zone |
| `businessKey` | business key contains this, case-insensitively |
| `q` | name or business key contains this, case-insensitively |
| `attr.<name>` | the [search attribute](/guides/searching-and-bulk-operations/) equals this — exactly for a keyword or bool, as a case-insensitive substring for a text, numerically for a number, as a moment for a date; repeatable |
| `attr.<name>.gt`, `.gte`, `.lt`, `.lte` | a number or date attribute is above / at least / below / at most this (a bare date as an upper bound is its last moment); a value that is neither a number nor a date is `400` |
| `query` | the same conditions as text, joined by `AND`: `customerId = "C-9" AND amount > 100`; malformed is `400` |
| `page`, `size` | zero-based page (default `0`) and page size (default `20`, `1`–`500`) |

```json
{"content": [{"id": "5f0c…", "definitionId": "order-fulfilment", "name": "Order fulfilment",
              "businessKey": "ORDER-1001", "status": "RUNNING", "completionPercentage": 40,
              "created": "2026-10-18T09:30:00.123", "started": "2026-10-18T09:30:00.140", "finished": null}],
 "page": 0, "size": 20, "totalElements": 1, "totalPages": 1}
```

`page` and `size` in the answer are the ones served: a page past the end is answered with the last real page. The listing carries no variables.

Every attribute condition is bound as a parameter; at most 10 per request. `GET /workflow/api/processes/{id}` carries the process's `searchAttributes` by name.

**Where it comes from** — the same rule as the console's Processes page. With the [process index](/guides/process-index/) on (`workflow.projection.enabled=true`) a listing filtered only by `q` and search attributes is answered from the read model, which has seen every shard; any other filter is answered by the engine's own store, because the index cannot apply it and a page that silently ignored a filter would be a wrong answer.

### Commands

A command answers `202 Accepted` with `{processId, command, stepExecutionId}` and a `Location` to the process. It is carried out asynchronously by the node that owns the process (its Kafka partition, its shard), under the process lock, by the same use case the console and MCP reach — read the process to see the outcome.

Before accepting, the API checks what a synchronous caller needs to know: the process exists (`404`) and its status allows the command (`409`, with `processStatus` in the body) — by the use case's own rule, so a `202` is a command the engine will carry out. A step retry also checks the step belongs to the process (`404`), failed (`ERROR`/`TIMEOUT`) and that the process is not finished (`409`). In a sharded fleet with the process index on, a process another shard owns is found through the index and the command routed to its shard.

### Manual operations

Set variables, complete a step and retry from a step change a live (`RUNNING`, `PAUSED` or `ERROR`) process by hand. Each needs a `reason` (`400` without one), answers `202` with `{processId, command, stepExecutionId, stepId}`, and is carried out — and recorded, with the caller and the reason, in the process log — by the node that owns the process. Before accepting, the API checks with the engine's own rules: `404` for an unknown process, step execution or step; `409` with the reason when the process or the step is not in a status the operation is done from, when a saga rollback is under way, for a retry from a step that has not run yet, or when another failure is not downstream of it. Semantics, locks and compensations: [Operating running processes](/guides/operating-running-processes/).

A [sensitive variable](/guides/sensitive-data-and-encryption/) reads `••••` in every view; reading one is `POST …/variables/{name}/reveal` with a `reason`, for a caller holding one of `workflow.security.sensitive.reveal-roles` — `400` without a reason or for a variable that is not sensitive, `403` without the role (an API key alone never reveals), and every reveal and refusal is recorded in the process log.

### Migration

`POST /{id}/migrate` moves a live process onto another version of its definition (`targetVersion`; absent for the definition as it is now), each step onto the target's step of the same id or the one `stepMapping` names, setting `variableMapping`'s variables; `reason` mandatory. It is planned before it is accepted: `409` lists the problems that refuse it, `400` for a version that does not exist (or any version but the current one in memory mode), `404` for an unknown process. `POST /{id}/migrate/dry-run` answers the plan — `fromVersion`, `toVersion`, `executable`, the effective `mapping`, every step's `state` and `action` (`REMAP`, `KEEP_TASK`, `REDISPATCH`, `REARM`, `HISTORY`, `DROP`, `CARRY`, `ADD`, `ADD_PASSED`) and the `problems` (`ERROR` refuses, `WARNING` is reported) — without changing anything; a process owned by another node is planned there (`409` here). Semantics and rules: [Migrating running processes](/guides/migrating-running-processes/).

The incidents endpoints group the failures waiting for an operator; a group retry is a `RETRY` batch operation over the incident — `202` with its `batchOperationId`, `Location` naming the batch — see [Incidents](/guides/operating-running-processes/#incidents).

### Updates

An update is a validated write that answers with a result, declared in the definition's `updates` — not an admin operation: it needs no reason and no operations role, only what reading or commanding the process needs (the definition's requirements, with flow authorization on). The body is the payload, any JSON. The answer is `200` with `{updateId, processId, name, status: COMPLETED, result, payload, requestedBy, requestedAt, completedAt, location}` (`payload` with the update's `sensitive` fields masked), or `202` + `Location` when `Prefer: wait=N` ran out first — the update still completes. Refusals: `400` (`INVALID_PAYLOAD`: not JSON or not what `accepts` allows), `404`, `409` (`NOT_ACCEPTING`, the detail says why), `422` (`REFUSED` by a guard, or `IDEMPOTENCY_KEY_REUSED`), `500` (`FAILED`: an expression of the update failed) — in every case nothing changed, and a refusal the owner recorded is replayed to a retry with the same `Idempotency-Key`. Semantics: [Updating running processes](/guides/updating-running-processes/).

## Batch operations

`/workflow/api/batch-operations` applies one command to every process a filter matches, in the background ([guide](/guides/searching-and-bulk-operations/#batch-operations)).

| Method and path | Does | Answers |
|---|---|---|
| `POST /workflow/api/batch-operations` | Create `{type, filter, variables?, remove?, stepId?, reason?, targetVersion?, fromVersion?, stepMapping?}` — `type` `CANCEL`, `RETRY`, `RESTART`, `PAUSE`, `RESUME`, `SET_VARIABLES` (`variables` to set, `remove` names; `reason` mandatory), `RETRY_FROM_STEP` (`stepId`; `reason` mandatory) or `MIGRATE` (`targetVersion`, `fromVersion`, `stepMapping`, `variables` to set; `reason` mandatory; the filter must name the definition); `filter` `{definitionId?, tag?, status?[], from?, to?, businessKey?, q?, onlyErrors?, query?, attributes?: {name: value}}`, at least one | `202` + `Location`, the batch `CREATED` with `matched` |
| `GET /workflow/api/batch-operations` | The most recent batches, newest first | `200` `BatchOperation[]` |
| `GET /workflow/api/batch-operations/{id}` | One batch: `status`, `matched`, `processed`, `succeeded`, `failed`, `skipped`, `skippedByReason` (`notApplicable`, `notAuthorized`, `gone`), `percent`, `errors` (a sample), `createdBy`, dates, `failure` | `200`; `404` |
| `POST /workflow/api/batch-operations/{id}/cancel` | Stop it: no further process is touched | `200`; `404`; `409` when it already finished |

Each process is commanded as the single-process endpoint would be, judged against the caller who created the batch: with flow authorization on, a process of a definition the caller may not operate is skipped (`notAuthorized`). An empty filter, an unknown type or status, or a malformed date or query is `400`.

## Security

Exactly the rule of the rest of `/workflow/api/**` — see [HTTP security](/reference/configuration/#http-security): the `X-Api-Key` header when `workflow.message-api.api-key` is set; otherwise the application's own Spring Security; otherwise `401` unless `workflow.message-api.allow-anonymous=true`. Bodies over `workflow.rest.max-body-bytes` are cut off unread (`413`), and identifiers and variables over the engine's input limits are refused with `400` naming the field.

With `workflow.security.flow-authorization.enabled=true`, a definition's `requiredScopes` / `requiredRoles` are checked against the caller (resolved by the same `CallerResolver` as the console and MCP):

- **starting** a process of it — as everywhere the engine starts one;
- **reading** one of its processes (`/{id}`, `/by-key`, `/steps`, `/variables`, `/logs`) and **commanding** it (cancel, pause, resume, retry, restart, step retry): a caller who may not start a workflow may not read its variables or stop its runs either.

The manual operations and the incident retry are admin operations: besides the definition's requirements (when flow authorization is on), the caller must hold every role in `workflow.security.operations.required-roles` — enforced whenever it is set, flow authorization on or off; empty by default.

A caller who lacks something gets `403` naming what is missing. The listing is not filtered by it — it shows what identifies a process and its status, never its variables.

## Errors

Errors are `application/problem+json` (RFC 9457):

| Status | When |
|---|---|
| `400` | no `definitionId`; a value over the input limits; a malformed body, status, date, `page` or `size` |
| `401` | missing or wrong `X-Api-Key`, or an anonymous request the engine refuses |
| `403` | flow authorization, or `workflow.security.operations.required-roles`, refused the caller |
| `404` | no such process, step execution of it, or definition (on start) |
| `409` | the business key belongs to another definition's process; the definition accepts no new processes; the process's status does not allow the command, or is not accepting an update |
| `422` | an update refused by its guard, or an `Idempotency-Key` reused for another update |
| `429` | too many callers waiting for an update on this node |
| `500` | an update whose `set` or `reply` failed to evaluate (nothing changed) |
| `413` | body over `workflow.rest.max-body-bytes` |

```json
{"type": "about:blank", "title": "Conflict", "status": 409,
 "detail": "Cannot retry process '5f0c…' while it is RUNNING.", "processStatus": "RUNNING"}
```

## OpenAPI

The committed description is at [`/openapi/eventconductor-api.yaml`](/openapi/eventconductor-api.yaml). A running engine can serve it too: add springdoc (an optional dependency of the engine) and switch it on.

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-api</artifactId>
    <version>3.1.1</version>
</dependency>
```

```properties
workflow.api.openapi.enabled=true
```

The engine then contributes the `eventconductor` group — `/v3/api-docs/eventconductor` (JSON) and `/v3/api-docs.yaml/eventconductor` — covering `/workflow/api/**` only, with the `X-Api-Key` security scheme. It does not switch springdoc on or off for the application's own endpoints; without the property it contributes nothing.

## Not over REST (yet)

- Editing a batch operation once created (cancel it and create another).
- Pausing or resuming a whole definition — the console and the `pauseWorkflow` / `resumeWorkflow` MCP tools.
- Analytics — the console and the `getWorkflowAnalytics` / `findBottleneck` MCP tools.
- In a sharded fleet, reading a process (`/{id}`, its steps, variables and log) answers from the shard that serves the request; a process owned by another shard is `404` there. Commands are routed (above).


## IDE test runs and frozen execution graphs

`POST /workflow/api/test-runs` accepts `{runId, definition, variables?}`: `runId` is a canonical
UUID, `definition` is the workflow JSON object (YAML is parsed by the IDE), and `variables`
is a JSON object. Requires `workflow.test-runs.enabled=true`; off by default, a call returns
`404` with an explanation. Normal API authentication applies, and the caller must hold every
role in `workflow.test-runs.required-roles` (falling back to
`workflow.security.operations.required-roles`) even when flow authorization is off; with no
role configured, or no identified caller, the call returns `403`. Roles or scopes declared by
the submitted document never grant this permission; with flow authorization on they are
required in addition.

A first accepted call returns `201` and a process `Location`; the same ID and payload returns
the retained instance with `200`. A conflicting document/input or concurrent submission returns
`409`. After an uncertain response, query the same ID or retry that exact submission. Once
retention purges the instance, its identifier no longer provides idempotency.

The definition is validated and frozen in the process; it is never saved to the definition
catalogue. It always runs under a private definition ID: `vscode-<id>-<runId>`, or
`vscode-<runId>` when the document has none. A catalogue definition already holding that ID
returns `409`. A test run therefore never counts as an instance of a published definition,
and it is refused by migration and its dry run with `409`. Test runs and their children are
left out of process analytics. The persisted test marker is
inherited by nested subprocesses and continuations. Children still resolve their deployed
definitions normally. `same` continuations use the executed snapshot; `latest` is rejected
with `400`, since the run has no published definition to continue on. This is a manual start: no cron/event registration or
startPolicy admission is performed. Workers and services have their usual, real effects.

`GET /workflow/api/processes/{id}/graph` returns `{process, testRun, parentProcessId, definition,
overlay, steps}`. `definition` is the effective graph topology from the process's snapshot plus
injected steps, not today's catalogue definition. A process without a snapshot returns `409`. Request blocks, credentials, agent inputs
and prompts are omitted; an agent's catalogue reference is retained. `process` carries the
effective version, masked current variables and continuation links. `overlay` provides node
states, errors, waits, retries and iteration summaries; `steps` includes historical executions
and their exact IDs. Logs remain at the existing `/processes/{id}/logs` route.

`GET /workflow/api/processes/{id}/children?page=0&size=20&stepId=sub` returns a page of direct
child processes, ordered by creation then ID. `stepId` is optional, `size` is 1–100. Every row
contains `parentStepExecutionId`, source `stepId`, zero-based `itemIndex` (or null), zero-based
`iteration`, effective definition version and continuation links. The relation keeps earlier
attempts and loop passes. It is not a recursive download. A forbidden child causes `403` for
that page. A purged process returns `404`; continuation links may still point to purged history.
Graph and children reads use normal process authorization even when starting tests is disabled.
