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

## TIMER

Durably pauses the workflow until a moment in time. No worker is involved: the step stays `PENDING` and the timer scheduler completes it once the due moment passes. The wait survives restarts — the due moment is recomputed from persisted state (the step definition, its start time and the variable snapshot), so a process can safely wait for days.

Wait for a duration, counted from the moment the step starts:

```json
{
  "id": "wait-72h",
  "type": "TIMER",
  "name": "Wait 72 hours",
  "duration": "PT72H",
  "preconditionStepId": "send-payment-link"
}
```

Wait until an absolute date carried by a process variable (e.g. a check-in date):

```json
{
  "id": "wait-until-checkin",
  "type": "TIMER",
  "name": "Wait until check-in date",
  "untilVariable": "checkinDate"
}
```

**Required fields:** `duration` or `untilVariable`

`duration` accepts an ISO 8601 duration string (`PT30M`, `PT72H`, `P3D`) or an integer in milliseconds. `untilVariable` names a process variable holding an ISO 8601 date (`2026-08-01`), date-time (`2026-08-01T15:00`) or offset date-time; it takes precedence over `duration`. If the referenced variable is missing or unparseable when the step starts, the step ends `ERROR` through the normal failure pipeline — the process never freezes silently. The `timeout` field is ignored for TIMER steps.

---

## MESSAGE

Durably pauses the workflow until a matching external message arrives — the equivalent of a BPMN message catch event or a Temporal signal. No worker is involved: the step stays `PENDING` and the engine completes it when a `MessageReceived` event with the same `messageName` and a matching correlation key is published on the upstream surface (Kafka topic, embedded publisher, or the `sendMessage` MCP tool).

Correlate by business key (the default):

```json
{
  "id": "wait-for-payment",
  "type": "MESSAGE",
  "name": "Wait for payment confirmation",
  "messageName": "payment-received",
  "preconditionStepId": "send-invoice",
  "timeout": 259200000
}
```

Correlate by a process variable via a JEXL expression:

```json
{
  "id": "wait-for-payment",
  "type": "MESSAGE",
  "name": "Wait for payment confirmation",
  "messageName": "payment-received",
  "correlationExpression": "orderId"
}
```

**Required fields:** `messageName`

A message matches a waiting step when the message name is equal and the message's `correlationKey` equals the key the step expects: the process `businessKey` by default, or — when `correlationExpression` is set — the value of that JEXL expression evaluated against the process variables (same context and same fail-closed semantics as `preconditionExpression`; an expression that cannot be evaluated matches nothing). On match, the message's variables are merged into the process variables (visible to all successor steps) and the step completes.

Delivery semantics:

- **Not buffered.** A message that matches no waiting step is ignored (and logged). Upstream delivery is at-least-once, so the sender retries — or sends again once the process reaches the waiting step.
- **Idempotent.** A duplicate delivery of an already-correlated message is ignored: the step only completes once.
- **Broadcast.** If several processes are waiting on the same `messageName` and correlation key, all of them are resumed.

`timeout` and `retries` keep their usual meaning: a waiting MESSAGE step that receives no message within `timeout` transitions to `TIMEOUT` and the normal retry/failure pipeline engages (a retry re-arms the wait).
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
