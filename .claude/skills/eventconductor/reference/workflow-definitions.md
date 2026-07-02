# Workflow definitions

A definition is JSON or YAML (`.json`/`.yaml`/`.yml`). In `embedded`+`memory` mode it loads
from `classpath:/workflows/`; in `jpa` mode it can also be imported from Git.

## Top-level fields

`id`, `name`, `version` (int), `description?`, `status` (`DRAFT`|`ACTIVE`|`DISABLED`|`ARCHIVED`),
`draftOfId?`, `limitConcurrentExecutions?`, `maxConcurrentExecutions?`, `enqueueOnLimit?`, `steps[]`.

Add `"$schema"` (JSON) or a `# yaml-language-server: $schema=...` comment (YAML) pointing at
`modules/workflow-engine/src/main/resources/workflow-definition-schema.json` for autocomplete.

## Step fields

| Field | Applies to | Notes |
|---|---|---|
| `id`, `type`, `name`, `description` | all | `type` ∈ ACTION/USER_TASK/PROCESS/FORK/JOIN/END |
| `preconditionStepId` | all | step that must complete first (defines ordering) |
| `preconditionExpression` | all | JEXL over variables; `false` ⇒ step `SKIPPED` |
| `parallel` | all | `true` inside a FORK branch |
| `topic` | ACTION | worker destination (Kafka mode; ignored embedded) |
| `formId` | USER_TASK | form to render |
| `childWorkflowDefinitionId` | PROCESS | child workflow id |
| `timeout` | all | ISO-8601 (`PT30S`,`PT1H30M`) or ms int; `0`=none |
| `retries` | all | auto-retry count on ERROR/TIMEOUT |
| `rollbackable` + `compensationStepId` | all | saga compensation on failure |

## Step types

- **ACTION** — dispatch to a worker. Kafka: publishes `TaskExecutionRequested` to `topic`.
  Embedded: routed to the single `EmbeddedTaskExecutor` (topic ignored).
- **USER_TASK** — pause for a human; creates a `FormExecution` for `formId` (needs `forms-engine`).
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
**Promote** copies content onto the original, bumps `version`, deletes the copy; running
processes are unaffected. One working copy per definition.
