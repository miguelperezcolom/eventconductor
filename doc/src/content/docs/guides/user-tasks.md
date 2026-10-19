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
5. The forms engine marks the `FormExecution` as `COMPLETED` and notifies the orchestrator
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
- `assignment` (optional) — who the task is for (assignee, candidate users and groups), its priority and due date, rendered from the process
- `escalation` (optional) — what happens while it stays open: reassign, notify, or route the process to another step

Assignment, visibility and claim rules, escalation, the task inbox and lifecycle events are described in [Human tasks: assignment and escalation](/guides/human-tasks-assignment-and-escalation/).

## Form execution lifecycle

| Status | Description |
|---|---|
| `PENDING` | Waiting for somebody to claim it (nobody holds it) |
| `ASSIGNED` | Held by a person — its assignee, or whoever claimed it — waiting for their submission |
| `COMPLETED` | User submitted the form |
| `CANCELLED` | The task (or its process) was cancelled |

## Querying pending user tasks

### Via the management UI

**Tasks** lists the open tasks you may work on — yours, the ones offered to you or your groups, and those that name nobody — most urgent first, with filters for *Mine*, *Claimable*, *My groups*, *Overdue only*, priority, process and business key. **Form Executions** (administration) lists every task.

### Via REST

`GET /forms/api/tasks?scope=mine&overdue=true` — and `claim`, `release`, `complete`, `assign` on `/forms/api/tasks/{id}/…`. See [the task inbox](/guides/human-tasks-assignment-and-escalation/#the-task-inbox).

### Via MCP (natural language)

```
"Show me all pending user tasks for the onboarding workflow"
"List the form executions that are still assigned"
"Get the details of form execution abc-123"
```

### Programmatically

```java
@Autowired
TaskInbox taskInbox;
@Autowired
TaskCallers taskCallers;

// The open tasks the current caller may see, most urgent first
TaskPage page = taskInbox.search(TaskQuery.open(), taskCallers.current());
```

## Submitting a form

### Via the management UI

Click on a pending task, fill the form fields, and click Submit.

### Programmatically

Over HTTP, `POST /forms/api/tasks/{id}/complete` with `{"values": {...}}`, as the person who holds the task.
In code, through `CompleteTaskUseCase` (this stores the values, marks the execution `COMPLETED` and
notifies the orchestrator with a `TaskStatusChanged` event) — `handle(command, caller)` to act for a
person (only the holder may), `handle(command)` for trusted code:

```java
@Autowired
CompleteTaskUseCase completeTaskUseCase;

completeTaskUseCase.handle(new CompleteTaskCommand(
    formExecutionId,
    List.of(
        new Value("decision", "APPROVE"),
        new Value("comments", "Looks good to me.")
    )
));
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
