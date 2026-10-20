# Calling and operating the engine (REST, MCP)

Everything under `/workflow/api/**` follows the access rule in [security.md](security.md): send
`X-Api-Key: <workflow.message-api.api-key>`, or the app's Spring Security credentials (standalone
apps: `-u admin:<SECURITY_PASSWORD>`), or run locally with `workflow.message-api.allow-anonymous=true`.
Examples below assume `workflow.message-api.api-key=dev-key`. Errors are `application/problem+json`. OpenAPI: `doc/public/openapi/eventconductor-api.yaml` (published
as `/openapi/eventconductor-api.yaml` on the docs site); live at `/v3/api-docs/eventconductor` when the app adds
`springdoc-openapi-starter-webmvc-api` and sets `workflow.api.openapi.enabled=true`.
Canonical: `doc/src/content/docs/reference/processes-rest-api.md`, `guides/operating-running-processes.md`,
`guides/searching-and-bulk-operations.md`, `guides/migrating-running-processes.md`.

## Processes

```bash
E=http://localhost:8080/workflow/api
curl -X POST "$E/processes" -H 'X-Api-Key: dev-key' -H 'Content-Type: application/json' \
     -d '{"definitionId":"booking-saga","businessKey":"B-1","variables":{"amount":120}}'
curl "$E/processes?definitionId=booking-saga&status=ERROR&page=0&size=20" -H 'X-Api-Key: dev-key'
curl "$E/processes/by-key?businessKey=B-1" -H 'X-Api-Key: dev-key'
curl "$E/processes/$ID/steps" -H 'X-Api-Key: dev-key'      # also /variables, /logs, /updates
curl -X POST "$E/processes/$ID/cancel" -H 'X-Api-Key: dev-key'   # also /pause /resume /retry /restart
```

| Call | Answer |
|---|---|
| `POST /processes` `{definitionId, businessKey?, variables?}` | 201 + Location; 200 existing process for the key (idempotent); 202 held back by `startPolicy`; 429 dropped; 404 unknown definition; 409 key held by another definition / definition disabled |
| `GET /processes` | filters `definitionId, tag, status` (repeat or comma list), `from, to`, `businessKey` (contains), `q`, `query`, `attr.<name>[.gt\|.gte\|.lt\|.lte]=v`, `page`, `size` (≤500) → `{content, page, size, totalElements, totalPages}` |
| `POST /processes/{id}/cancel\|pause\|resume\|retry\|restart` | 202 (carried out by the owning node); 404; 409 when the status does not allow it. `retry` = rerun failed steps of an ERROR/CANCELLED process; `restart` = from the top |

Java: `ProcessUpstreamEventUseCase` (start), `CancelProcessUseCase`, `RetryProcessUseCase`,
`RestartProcessUseCase`, `PauseProcessUseCase`, `ResumeProcessUseCase` — see [api.md](api.md).

## Messages, events, invocations, updates

| Call | Purpose |
|---|---|
| `POST /messages` `{messageName, correlationKey, variables}` | resume a `WAIT_FOR_MESSAGE`/`awaitReply` (202; unmatched messages are dropped, not buffered) |
| `POST /events/{destination}` (structured CloudEvent) | trigger workflows / resume `onEvent` waits |
| `POST /definitions/{id}/invocations` (`Idempotency-Key`, `Prefer: wait=N`) | synchronous invocation → 200 reply / 202 + Location / 502 failure |
| `POST /processes/{id}/updates/{name}` (optional `Idempotency-Key`, `Prefer: wait=N`) | process update → 200 result / 202 / 400 schema / 409 not accepting / 422 refused |
| `POST /definitions/{id}/update-with-start` `{businessKey, variables, update, payload}` | start-or-update |
| `/triggers`, `/schedules` | list/create/edit/pause/resume event triggers and schedules (`GET …/yaml` exports a file entry) |

## Repairing a live process (audited, `reason` required)

| Call | Does |
|---|---|
| `POST /processes/{id}/variables` `{set: {name: value}, remove: [names], reason}` | change variables (RUNNING/PAUSED/ERROR) |
| `POST /processes/{id}/steps/{stepExecutionId}/complete` `{variables?, reason}` | complete a stuck/failed step by hand |
| `POST /processes/{id}/retry-from/{stepId}` `{reason}` | rerun from a step (definition step id); downstream reopened, locks re-taken |
| `POST /processes/{id}/variables/{name}/reveal` `{reason}` | reveal a sensitive value (`workflow.security.sensitive.reveal-roles`) |

Roles: `workflow.security.operations.required-roles`. Refused on finished processes and during a saga
rollback. Each operation is logged with `operator:<subject>` and counted in `eventconductor.processes.operations`.

## Incidents

Failed steps grouped by definition + step + error family.
`GET /incidents?definitionId=`, `GET /incidents/{key}/processes`, `POST /incidents/{key}/retry {reason}`
(runs as a RETRY batch). Console: Incidents page.

## Search attributes and queries

```yaml
searchAttributes:
  customerId: "${customerId}"
  amount: { value: "${total}", type: number }      # keyword (default) | text | number | date | bool
```
At most 20; recomputed whenever the process is saved; part of the version; never from a sensitive variable.
Query language: `name op value (AND name op value)*`, ops `= > >= < <=`, values bare or quoted;
no OR / parentheses / negation; ≤ 10 conditions. REST: `GET /processes?query=customerId = "C-9" AND amount > 100`
(URL-encode it) or `attr.amount.gte=100`; MCP `listProcesses(query)`.

## Batch operations

```bash
curl -X POST "$E/batch-operations" -H 'X-Api-Key: dev-key' -H 'Content-Type: application/json' -d '{
  "type": "RETRY_FROM_STEP", "stepId": "charge", "reason": "gateway fixed",
  "filter": {"definitionId": "booking-saga", "status": ["ERROR"], "query": "amount > 100"}}'
```
Types: `CANCEL`, `RETRY`, `RESTART`, `PAUSE`, `RESUME`, `SET_VARIABLES` (`variables`, `remove`, `reason`),
`RETRY_FROM_STEP` (`stepId`, `reason`), `MIGRATE` (`targetVersion`, `fromVersion`, `stepMapping`,
`variables`, `reason`). `filter` (non-empty): `definitionId, tag, status[], from, to, businessKey, q,
onlyErrors, query, attributes{}`. 202 + Location; `GET /batch-operations[/{id}]`, `POST /batch-operations/{id}/cancel`.
Runs in the background on one node at a time, throttled (`workflow.batch-operations.max-per-second`, 50),
resumable; each process authorized and status-checked (refusals counted as skipped).

## Migration

Move running processes to another version of their definition.
- Dry run: `POST /processes/{id}/migrate/dry-run {targetVersion?, stepMapping?: {old: new, old2: ""}, variableMapping?}`
  → plan with per-step actions and `problems` (ERROR blocks, WARNING informs).
- Migrate: `POST /processes/{id}/migrate {…, reason}` (RUNNING/PAUSED/ERROR; refused during rollback).
- Many: batch `MIGRATE`; MCP `dryRunProcessMigration`, `migrateProcess`, `migrateProcessesInBatch`.
- CI: `mvn eventconductor:check-migration -Dfrom=v3` (git ref, path or `current`) fails when a waiting
  step of the old version has no compatible counterpart. Mapping file: `{<workflowId>: {<old step>: <new step>}}`.
- Memory persistence keeps no definition history: you can only migrate to the current version.

## Retention

`retention: P30D` or `{completed: P30D, error: P180D, compensationFailed: forever}` (keys `completed`,
`cancelled`, `error`, `compensated`, `compensationFailed`; days-to-seconds durations or `forever`).
Engine defaults `workflow.processes.retention`, `workflow.processes.retention-by-status.<status>` are
OFF unless set. Purge job `workflow.processes.purge.{enabled, interval, batch-size, max-per-pass}`.
A purged ERROR process cannot be retried; its business key is free again.

## MCP tools (Spring AI MCP server, Mateu `/mateu/mcp`)

- Processes: `listProcesses`, `getProcessDetails`, `findProcessByBusinessKey`,
  `listRunsOfBusinessKey`, `listInFlightProcesses`, `countProcessesByStatus`, `getProcessLogs`,
  `retryProcess`, `restartProcess`, `pauseProcess`, `resumeProcess`, `pauseWorkflow`, `resumeWorkflow`,
  `sendMessage`, `getWorkflowAnalytics`, `findBottleneck`, `importWorkflowDefinitionsFromGit`.
- Operations: `setProcessVariables`, `completeStepManually`, `retryProcessFromStep`,
  `dryRunProcessMigration`, `migrateProcess`, `listIncidents`, `getIncidentProcesses`, `retryIncident`,
  `revealProcessVariable`.
- Batch: `createBatchOperation`, `migrateProcessesInBatch`, `listBatchOperations`, `getBatchOperation`,
  `cancelBatchOperation`. (No `cancelProcess` tool — cancel with a CANCEL batch.)
- Updates: `updateProcess`, `updateWithStart`, `listProcessUpdates`.
- Schedules/triggers: `listSchedules`, `createSchedule`, `pauseSchedule`, `runScheduleNow`, …,
  `listEventTriggers`, `createEventTrigger`, `publishInboundEvent`, `listReceivedEvents`, `listWorkflowDefinitions`.
- Forms: `searchTasks`, `listForms`, … Rules: `listRules`, `evaluateRule`, …
- There is no "start process" MCP tool: start over REST, a trigger (`publishInboundEvent`), or `updateWithStart`.

Claude Desktop setup: `doc/src/content/docs/guides/mcp-claude-desktop.md`. MCP endpoints need Spring
Security or `allow-anonymous` (403 otherwise).
