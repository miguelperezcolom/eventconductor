---
title: User Tasks
description: Pausing workflows for human input using USER_TASK steps and form executions.
---

A `USER_TASK` step pauses a workflow process until a human submits a form. This is useful for approval flows, data entry, and any step that requires human judgement.

## How it works

1. The orchestrator reaches a `USER_TASK` step
2. The forms engine creates a **FormExecution** — a specific instance of the form linked to this process and step
3. The step transitions to `RUNNING` and the process pauses
4. The user opens the management UI (or a custom front-end), finds the pending task, fills the form, and submits
5. The forms engine marks the `FormExecution` as `Completed` and notifies the orchestrator
6. The step transitions to `COMPLETED` and the workflow advances

## Defining a USER_TASK step

```json
{
  "id": "approve-expense",
  "type": "USER_TASK",
  "name": "Manager Approval",
  "formId": "expense-approval-form",
  "preconditionStepId": "register-expense",
  "timeout": 86400000
}
```

- `formId` references a form definition by its ID
- `timeout` (optional) — if the user doesn't submit within this time, the step transitions to `TIMEOUT`

## Form execution lifecycle

| Status | Description |
|---|---|
| `Assigned` | Form created, waiting for user submission |
| `Completed` | User submitted the form |

## Querying pending user tasks

### Via the management UI

Navigate to **Form Executions** in the management UI to see all pending and completed user tasks. Filter by workflow, step, or assignee.

### Via MCP (natural language)

```
"Show me all pending user tasks for the onboarding workflow"
"List the form executions that are still assigned"
"Get the details of form execution abc-123"
```

### Programmatically

```java
@Autowired
FormExecutionRepository formExecutionRepository;

List<FormExecution> pending = formExecutionRepository
    .findByStatus(FormExecutionStatus.Assigned);
```

## Submitting a form

### Via the management UI

Click on a pending task, fill the form fields, and click Submit.

### Via the API

```http
POST /forms/api/executions/{formExecutionId}/submit
Content-Type: application/json

{
  "values": [
    { "fieldId": "decision", "value": "APPROVE" },
    { "fieldId": "comments", "value": "Looks good to me." }
  ]
}
```

## Submitted values as process variables

The submitted form values are converted to process variables and merged into the process. They are then available to subsequent steps and JEXL expressions:

```json
{
  "id": "notify-requester",
  "type": "ACTION",
  "name": "Notify Requester",
  "topic": "notification-service",
  "preconditionStepId": "approve-expense",
  "preconditionExpression": "decision == 'APPROVE'"
}
```
