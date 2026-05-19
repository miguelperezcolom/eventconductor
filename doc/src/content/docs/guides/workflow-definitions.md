---
title: Workflow Definitions
description: The EventConductor JSON workflow DSL — steps, branching, retries, and more.
---

Workflow definitions are JSON files that describe the steps of a business process. They are version-controlled, human-readable, and reviewable in a pull request.

In `embedded` + `memory` mode, definitions are loaded from `classpath:/workflows/*.json` at startup. In `jpa` persistence mode, they can also be imported from Git via the MCP tool `importWorkflowDefinitionsFromGit`.

## File format

```json
{
  "id": "my-workflow",
  "name": "My Workflow",
  "version": 1,
  "description": "Optional description",
  "status": "ACTIVE",
  "limitConcurrentExecutions": false,
  "maxConcurrentExecutions": 0,
  "enqueueOnLimit": false,
  "steps": [...]
}
```

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique workflow identifier |
| `name` | string | Human-readable name |
| `version` | integer | Version number |
| `description` | string | Optional description |
| `status` | enum | `DRAFT` \| `ACTIVE` \| `DISABLED` \| `ARCHIVED` |
| `draftOfId` | string | ID of the production definition this is a working copy of. `null` for production definitions. Set automatically by the UI. |
| `limitConcurrentExecutions` | boolean | Cap concurrent running instances |
| `maxConcurrentExecutions` | integer | Max instances (if limit enabled) |
| `enqueueOnLimit` | boolean | Queue new instances when limit reached |

### Definition statuses

| Status | Description |
|---|---|
| `DRAFT` | Under construction, not executable |
| `ACTIVE` | Ready to accept new process instances |
| `DISABLED` | No new instances allowed; running ones continue |
| `ARCHIVED` | Retired definition |

## Working copies

A **working copy** is a `DRAFT` clone of an existing production definition. It lets you iterate on a workflow safely while the original continues to run in production.

### Lifecycle

```
Production definition (ACTIVE)
        │
        │  Create working copy
        ▼
Working copy (DRAFT, draftOfId = <original id>)
        │  edit / test / iterate
        │
        │  Promote to production
        ▼
Production definition updated (version + 1), working copy deleted
```

### Rules

- Only one working copy per definition is allowed at a time.
- The working copy has `status = DRAFT` and its `draftOfId` field points to the original definition's ID.
- Promoting copies all content (steps, description, concurrency settings) onto the original, increments `version` by one, and deletes the working copy. The original's `status` is preserved.
- The `[draft]` suffix is stripped from the name automatically on promotion.
- Processes running against the original definition are unaffected until promotion.

## Step fields

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique identifier within the workflow |
| `type` | enum | — | `ACTION` \| `USER_TASK` \| `PROCESS` \| `FORK` \| `JOIN` \| `END` |
| `name` | string | — | Human-readable name |
| `description` | string | — | Optional description |
| `preconditionStepId` | string | — | Step that must complete before this one starts |
| `preconditionExpression` | string | — | JEXL expression; step is skipped if evaluates to `false` |
| `parallel` | boolean | `false` | Allows concurrent execution with other parallel steps |
| `topic` | string | — | Worker topic/destination (ACTION only) |
| `formId` | string | — | Form identifier (USER_TASK only) |
| `childWorkflowDefinitionId` | string | — | Child workflow ID (PROCESS only) |
| `timeout` | integer (ms) | `0` | Max execution time; `0` = no timeout |
| `retries` | integer | `0` | Auto-retry attempts on ERROR or TIMEOUT |
| `rollbackable` | boolean | `false` | Trigger compensation step on failure |
| `compensationStepId` | string | — | Step to run as compensation (requires `rollbackable: true`) |

## Examples

### Linear workflow

```json
{
  "id": "order-processing",
  "name": "Order Processing",
  "version": 1,
  "status": "ACTIVE",
  "steps": [
    {
      "id": "validate",
      "type": "ACTION",
      "name": "Validate Order",
      "topic": "order-validator"
    },
    {
      "id": "charge",
      "type": "ACTION",
      "name": "Charge Payment",
      "topic": "payment-service",
      "preconditionStepId": "validate",
      "timeout": 30000,
      "retries": 2
    },
    {
      "id": "ship",
      "type": "ACTION",
      "name": "Ship Order",
      "topic": "fulfillment-service",
      "preconditionStepId": "charge"
    },
    {
      "id": "end",
      "type": "END",
      "name": "Done",
      "preconditionStepId": "ship"
    }
  ]
}
```

### Workflow with human approval

```json
{
  "id": "expense-approval",
  "name": "Expense Approval",
  "version": 1,
  "status": "ACTIVE",
  "steps": [
    {
      "id": "submit",
      "type": "ACTION",
      "name": "Register Expense",
      "topic": "expense-service"
    },
    {
      "id": "approve",
      "type": "USER_TASK",
      "name": "Manager Approval",
      "formId": "expense-approval-form",
      "preconditionStepId": "submit"
    },
    {
      "id": "process",
      "type": "ACTION",
      "name": "Process Payment",
      "topic": "finance-service",
      "preconditionStepId": "approve"
    },
    {
      "id": "end",
      "type": "END",
      "name": "Done",
      "preconditionStepId": "process"
    }
  ]
}
```

### Conditional branching with JEXL

```json
{
  "id": "order-with-review",
  "name": "Order with optional review",
  "version": 1,
  "status": "ACTIVE",
  "steps": [
    {
      "id": "check",
      "type": "ACTION",
      "name": "Check Order",
      "topic": "order-checker"
    },
    {
      "id": "review",
      "type": "USER_TASK",
      "name": "Manual Review",
      "formId": "review-form",
      "preconditionStepId": "check",
      "preconditionExpression": "amount > 1000"
    },
    {
      "id": "fulfill",
      "type": "ACTION",
      "name": "Fulfill Order",
      "topic": "fulfillment-service",
      "preconditionStepId": "check"
    },
    {
      "id": "end",
      "type": "END",
      "name": "Done",
      "preconditionStepId": "fulfill"
    }
  ]
}
```

### Saga with compensation

```json
{
  "id": "booking-saga",
  "name": "Booking Saga",
  "version": 1,
  "status": "ACTIVE",
  "steps": [
    {
      "id": "reserve-hotel",
      "type": "ACTION",
      "name": "Reserve Hotel",
      "topic": "hotel-service",
      "rollbackable": true,
      "compensationStepId": "cancel-hotel",
      "retries": 2
    },
    {
      "id": "reserve-flight",
      "type": "ACTION",
      "name": "Reserve Flight",
      "topic": "flight-service",
      "preconditionStepId": "reserve-hotel",
      "rollbackable": true,
      "compensationStepId": "cancel-flight"
    },
    {
      "id": "cancel-hotel",
      "type": "ACTION",
      "name": "Cancel Hotel Reservation",
      "topic": "hotel-service"
    },
    {
      "id": "cancel-flight",
      "type": "ACTION",
      "name": "Cancel Flight Reservation",
      "topic": "flight-service"
    },
    {
      "id": "end",
      "type": "END",
      "name": "Done",
      "preconditionStepId": "reserve-flight"
    }
  ]
}
```
