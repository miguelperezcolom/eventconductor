---
title: Step Types
description: Reference for all step types available in EventConductor workflow definitions.
---

## ACTION

Dispatches a task to a worker microservice (or embedded bean). The workflow pauses until the worker reports completion.

```json
{
  "id": "process-payment",
  "type": "ACTION",
  "name": "Process Payment",
  "topic": "payment-service",
  "timeout": 30000,
  "retries": 3,
  "rollbackable": true,
  "compensationStepId": "refund-payment"
}
```

**Required fields (Kafka mode):** `topic`

The `topic` field specifies the Kafka topic to which `TaskExecutionRequested` is published in **Kafka mode**. In **embedded mode** the field is ignored — all ACTION steps are routed to the single registered `EmbeddedTaskExecutor` bean regardless of its value. It may be omitted when targeting embedded-only workflows.

---

## USER_TASK

Pauses the workflow until a human submits a form. Creates a `FormExecution` instance in the forms engine.

```json
{
  "id": "manager-approval",
  "type": "USER_TASK",
  "name": "Manager Approval",
  "formId": "expense-approval-form",
  "preconditionStepId": "register-expense",
  "timeout": 86400000
}
```

**Required fields:** `formId`

The `formId` references a form definition stored in the forms engine.

---

## RULE

Evaluates a business rule from the rule engine against the process variables. The rule's outputs are merged back into the process variables.

```json
{
  "id": "apply-discount",
  "type": "RULE",
  "name": "Apply the discount rule",
  "ruleId": "high-value-order",
  "preconditionStepId": "register-order"
}
```

**Required fields:** `ruleId`

The `ruleId` references a rule definition (expression rule or decision table) stored in the rule engine. The engine dispatches the step with `taskId=evaluate-rule`; any app embedding `rule-runtime` (or `rule-standalone-app`) evaluates it. See [Rule Evaluation](/guides/rule-evaluation/).

---

## PROCESS

Starts a child workflow as a sub-process. The parent step completes when the child process completes.

```json
{
  "id": "run-kyc",
  "type": "PROCESS",
  "name": "Run KYC Check",
  "childWorkflowDefinitionId": "kyc-workflow",
  "preconditionStepId": "create-customer"
}
```

**Required fields:** `childWorkflowDefinitionId`

Variables from the parent process are passed to the child process. Output variables from the child are merged back into the parent.

---

## FORK

Starts multiple parallel branches simultaneously. All steps with `parallel: true` and the same `preconditionStepId` as the FORK (or following the FORK) will execute in parallel.

```json
{
  "id": "fork-notifications",
  "type": "FORK",
  "name": "Send Notifications",
  "preconditionStepId": "process-order"
},
{
  "id": "send-email",
  "type": "ACTION",
  "name": "Send Email",
  "topic": "email-service",
  "preconditionStepId": "fork-notifications",
  "parallel": true
},
{
  "id": "send-sms",
  "type": "ACTION",
  "name": "Send SMS",
  "topic": "sms-service",
  "preconditionStepId": "fork-notifications",
  "parallel": true
}
```

---

## JOIN

Waits for all parallel branches to complete before proceeding.

```json
{
  "id": "join-notifications",
  "type": "JOIN",
  "name": "All notifications sent",
  "preconditionStepId": "send-email"
}
```

Typically placed after the last parallel step. The JOIN step completes when all steps in the parallel branch have completed.

---

## END

Marks the workflow as complete. The process transitions to `COMPLETED`.

```json
{
  "id": "end",
  "type": "END",
  "name": "Done",
  "preconditionStepId": "last-step"
}
```

Every workflow must have exactly one END step. If a workflow has parallel branches, use a JOIN step before the END.

---

## Common fields (all step types)

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique identifier within the workflow |
| `type` | enum | — | Step type (see above) |
| `name` | string | — | Human-readable name |
| `description` | string | — | Optional description |
| `preconditionStepId` | string | — | Step that must complete before this one starts |
| `preconditionExpression` | string | — | JEXL expression; step skipped if evaluates to `false` |
| `parallel` | boolean | `false` | Allow concurrent execution with other parallel steps |
| `timeout` | integer (ms) | `0` | Max execution time; `0` = no timeout |
| `retries` | integer | `0` | Auto-retry attempts on ERROR or TIMEOUT |
| `rollbackable` | boolean | `false` | Trigger compensation step on failure |
| `compensationStepId` | string | — | Step to run as compensation |
