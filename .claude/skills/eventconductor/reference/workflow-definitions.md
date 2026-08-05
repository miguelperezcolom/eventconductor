# Workflow definitions

A definition is JSON or YAML (`.json`/`.yaml`/`.yml`). In `embedded`+`memory` mode it loads
from `classpath:/workflows/`; in `jpa` mode it can also be imported from Git.

## Top-level fields

`id`, `name`, `version` (int), `description?`, `status` (`DRAFT`|`ACTIVE`|`DISABLED`|`ARCHIVED`),
`paused?` (runtime pause flag, orthogonal to `status` and NOT an authoring decision —
toggled by pause/resume at runtime; while true all the definition's processes are held and
new instances, cron included, are created born-`PAUSED`; in the schema, default `false`,
only so exports round-trip),
`draftOfId?`, `limitConcurrentExecutions?`, `maxConcurrentExecutions?`, `enqueueOnLimit?`,
`cronExpression?` (Spring cron: start a new instance per occurrence, multi-pod safe),
`defaultMaxStepExecutions?` (validated metadata, not enforced at runtime today), `steps[]`.

Add `"$schema"` (JSON) or a `# yaml-language-server: $schema=...` comment (YAML) pointing at
`modules/workflow-engine/src/main/resources/workflow-definition-schema.json` for autocomplete.

## Step fields

| Field | Applies to | Notes |
|---|---|---|
| `id`, `type`, `name`, `description` | all | `type` ∈ START/ACTION/USER_TASK/RULE/TIMER/WAIT_FOR_MESSAGE/SEND_MESSAGE/PROCESS/FORK/JOIN/END |
| `preconditionStepId` | all | single step that must complete first |
| `preconditionStepIds` | all | steps that must ALL complete first; wins over the singular form when non-empty |
| `preconditionExpression` | all | JEXL guard; while falsy the step never runs (stays `CREATED`, → `CANCELLED` at `END`) |
| `parallel` | all | **deprecated and ignored** — every eligible step runs concurrently |
| `topic` | ACTION | worker destination (Kafka mode; ignored embedded) |
| `formId` | USER_TASK | form to render |
| `ruleId` | RULE | rule to evaluate (rule-engine catalog) |
| `duration` | TIMER | wait length; ISO-8601 or ms |
| `untilVariable` | TIMER | variable holding an ISO-8601 date/date-time; wins over `duration` |
| `messageName` | WAIT_FOR_MESSAGE, SEND_MESSAGE | message to wait for / emit; **required** for both |
| `correlationExpression` | WAIT_FOR_MESSAGE, SEND_MESSAGE | JEXL producing the correlation key; **required** for both (use `businessKey` for the business key) |
| `messageVariables` | SEND_MESSAGE | array of process-variable names the message carries; empty/absent = none |
| `childWorkflowDefinitionId` | PROCESS | child workflow id; **required**, must differ from the workflow's own id |
| `outputVariables` | PROCESS | child variables copied back to the parent on completion; empty/absent = none |
| `timeout` | all | ISO-8601 (`PT30S`,`PT1H30M`) or ms int; `0`=none |
| `retries` | all | auto-retry count on ERROR/TIMEOUT |
| `rollbackable` + `compensationStepId` | all | saga compensation on failure |
| `maxSuccessfulExecutions` | all | validated metadata, not enforced at runtime today |

## Step types

- **START** — entry point: no worker, completes instantly at process creation. Must have no
  preconditions; several STARTs = concurrent entry branches.
- **ACTION** — dispatch to a worker. Kafka: publishes `TaskExecutionRequested` to `topic`.
  Embedded: routed to the single `EmbeddedTaskExecutor` (topic ignored).
- **USER_TASK** — pause for a human; creates a `FormExecution` for `formId` (needs `forms-engine`).
- **RULE** — evaluate a business rule (`ruleId`) from the rule catalog; outputs merge into process variables (needs `rule-runtime` on the evaluating side; taskId is `evaluate-rule`).
- **TIMER** — durable wait, no worker: `duration` (ISO-8601 or ms) or `untilVariable` (variable with an
  ISO-8601 date/date-time; wins). Survives restarts; a misconfigured timer ends the step `ERROR`.
- **WAIT_FOR_MESSAGE** (previously `MESSAGE`; old name still deserializes) — durable wait for a
  `MessageReceived(messageName, correlationKey, variables)` (via `POST /workflow/api/messages`, Kafka
  `upstream` as `"type":"message-received"`, MCP `sendMessage`, or a SEND_MESSAGE step). Correlates on
  the required JEXL `correlationExpression` over variables (fail-closed; use `businessKey` for
  the business key). Message variables merge into the process; unmatched messages are ignored, not buffered.
- **SEND_MESSAGE** — the throw side, no worker: on start, evaluates `correlationExpression`, emits
  `MessageReceived(messageName, correlationKey, messageVariables)` through the outbox and completes
  immediately. Fire-and-forget (delivery not acknowledged; unmatched messages discarded). Missing
  fields or an unevaluable correlation key → step `ERROR` (fails loud, unlike precondition guards).
- **PROCESS** — run `childWorkflowDefinitionId` as a child workflow, no worker: the child starts
  with ALL parent variables and the deterministic businessKey `parent:<stepExecutionId>`
  (idempotent); the parent step waits `PENDING`; on child `COMPLETED` only the child variables
  named in `outputVariables` are copied back (absent = none); child `ERROR`/`CANCELLED` →
  parent step `ERROR` (normal retry/compensation). `timeout` bounds the wait. Parent-side
  `CANCELLED`/`ERROR`/`TIMEOUT` (retries exhausted) cancels a still-running child (cascades to
  grandchildren); while retries remain the child keeps running and a retried step re-attaches
  to it.
- **FORK / JOIN** — no-worker nodes that complete instantly. FORK is the explicit fan-out
  (every step preconditioned on it starts concurrently); JOIN is the barrier — its
  `preconditionStepIds` must ALL complete.
- **END** — exactly one; process → `COMPLETED`. Put a JOIN before it if there are branches.

## Ordering

Steps execute by **pure data flow**: a step becomes eligible when ALL its preconditions
(`preconditionStepIds` / `preconditionStepId`) have completed and its guard holds; every
eligible step starts concurrently. Array order is irrelevant; `parallel` is ignored.
**Roots rule:** a step with no preconditions does not run — it must be a `START`, a
`WAIT_FOR_MESSAGE` beginning a flow, or another step's `compensationStepId` (started by the
rollback pipeline). Anything else with no preconditions is rejected at load. Migrating an old
definition = add one `START` step and point the old first steps at it.

## Patterns

Human approval:
```json
{ "id": "approve", "type": "USER_TASK", "name": "Manager Approval",
  "formId": "expense-approval-form", "preconditionStepId": "submit", "timeout": 86400000 }
```

Conditional (JEXL) — skip when false:
```json
{ "id": "review", "type": "USER_TASK", "formId": "review-form",
  "preconditionStepId": "check", "preconditionExpression": "amount > 1000" }
```

Parallel (fan-out + barrier):
```json
{ "id": "fork",  "type": "FORK", "name": "Fork", "preconditionStepId": "process" },
{ "id": "email", "type": "ACTION", "topic": "email-service", "preconditionStepId": "fork" },
{ "id": "sms",   "type": "ACTION", "topic": "sms-service",   "preconditionStepId": "fork" },
{ "id": "join",  "type": "JOIN", "name": "Join", "preconditionStepIds": ["email", "sms"] }
```

Child workflow:
```json
{ "id": "run-kyc", "type": "PROCESS", "name": "Run KYC",
  "childWorkflowDefinitionId": "kyc-workflow", "outputVariables": ["kycResult"],
  "preconditionStepId": "create-customer", "timeout": "PT1H" }
```

Saga with compensation:
```json
{ "id": "reserve-hotel", "type": "ACTION", "topic": "hotel-service",
  "preconditionStepId": "start",
  "rollbackable": true, "compensationStepId": "cancel-hotel", "retries": 2 },
{ "id": "cancel-hotel",  "type": "ACTION", "topic": "hotel-service" }
```
Compensation steps are ordinary `ACTION` steps with **no preconditions**: they are named by
the step they undo, and the compensation pipeline starts them directly to undo completed work
when the process fails. Older definitions anchor them to the step they compensate with
`"preconditionExpression": "false"` and still work.

## Working copies (jpa mode)

A `DRAFT` clone (`draftOfId` = original id) you can edit while production keeps running.
**Promote** works on any `DRAFT`: with a `draftOfId`, content is copied onto the original,
`version` bumped, copy deleted (running processes unaffected); a standalone draft
(`draftOfId == null`) is simply activated in place. One working copy per definition.
