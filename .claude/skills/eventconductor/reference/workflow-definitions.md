# Workflow definitions

A definition is JSON or YAML (`.json`/`.yaml`/`.yml`). In `embedded`+`memory` mode it loads
from `classpath:/workflows/`; in `jpa` mode it can also be imported from Git.

## Top-level fields

`id`, `name`, `version` (int), `description?`, `status` (`DRAFT`|`ACTIVE`|`DISABLED`|`ARCHIVED`),
`draftOfId?`, `limitConcurrentExecutions?`, `maxConcurrentExecutions?`, `enqueueOnLimit?`,
`cronExpression?` (Spring cron: start a new instance per occurrence, multi-pod safe),
`defaultMaxStepExecutions?` (validated metadata, not enforced at runtime today), `steps[]`.

Add `"$schema"` (JSON) or a `# yaml-language-server: $schema=...` comment (YAML) pointing at
`modules/workflow-engine/src/main/resources/workflow-definition-schema.json` for autocomplete.

## Step fields

| Field | Applies to | Notes |
|---|---|---|
| `id`, `type`, `name`, `description` | all | `type` ∈ ACTION/USER_TASK/RULE/TIMER/WAIT_FOR_MESSAGE/SEND_MESSAGE/PROCESS/FORK/JOIN/END |
| `preconditionStepId` | all | step that must complete first (defines ordering) |
| `preconditionExpression` | all | JEXL guard; while falsy the step never runs (stays `CREATED`, → `CANCELLED` at `END`) |
| `parallel` | all | `true` inside a FORK branch |
| `topic` | ACTION | worker destination (Kafka mode; ignored embedded) |
| `formId` | USER_TASK | form to render |
| `ruleId` | RULE | rule to evaluate (rule-engine catalog) |
| `duration` | TIMER | wait length; ISO-8601 or ms |
| `untilVariable` | TIMER | variable holding an ISO-8601 date/date-time; wins over `duration` |
| `messageName` | WAIT_FOR_MESSAGE, SEND_MESSAGE | message to wait for / emit; **required** for both |
| `correlationExpression` | WAIT_FOR_MESSAGE, SEND_MESSAGE | JEXL producing the correlation key; **required** for both (use `businessKey` for the business key) |
| `messageVariables` | SEND_MESSAGE | array of process-variable names the message carries; empty/absent = none |
| `childWorkflowDefinitionId` | PROCESS | child workflow id |
| `timeout` | all | ISO-8601 (`PT30S`,`PT1H30M`) or ms int; `0`=none |
| `retries` | all | auto-retry count on ERROR/TIMEOUT |
| `rollbackable` + `compensationStepId` | all | saga compensation on failure |
| `maxSuccessfulExecutions` | all | validated metadata, not enforced at runtime today |

## Step types

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
- **PROCESS** — run `childWorkflowDefinitionId` as a sub-process; variables pass down and merge back up.
- **FORK / JOIN** — FORK starts branches whose steps set `parallel: true`; JOIN waits for all.
- **END** — exactly one; process → `COMPLETED`. Put a JOIN before it if there are branches.

## Ordering

Steps execute by **data flow**: a step becomes eligible when its `preconditionStepId` step
completes. A step with no `preconditionStepId` starts immediately. Array order is irrelevant.

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

Parallel:
```json
{ "id": "fork",  "type": "FORK", "preconditionStepId": "process" },
{ "id": "email", "type": "ACTION", "topic": "email-service", "preconditionStepId": "fork", "parallel": true },
{ "id": "sms",   "type": "ACTION", "topic": "sms-service",   "preconditionStepId": "fork", "parallel": true },
{ "id": "join",  "type": "JOIN", "preconditionStepId": "email" }
```

Saga with compensation:
```json
{ "id": "reserve-hotel", "type": "ACTION", "topic": "hotel-service",
  "rollbackable": true, "compensationStepId": "cancel-hotel", "retries": 2 },
{ "id": "cancel-hotel",  "type": "ACTION", "topic": "hotel-service" }
```
Compensation steps are ordinary `ACTION` steps, usually with no `preconditionStepId`; they run
to undo completed work when the process fails.

## Working copies (jpa mode)

A `DRAFT` clone (`draftOfId` = original id) you can edit while production keeps running.
**Promote** works on any `DRAFT`: with a `draftOfId`, content is copied onto the original,
`version` bumped, copy deleted (running processes unaffected); a standalone draft
(`draftOfId == null`) is simply activated in place. One working copy per definition.
