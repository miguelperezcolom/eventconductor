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

## WAIT_FOR_MESSAGE

Durably pauses the workflow until a matching external message arrives — the equivalent of a BPMN message catch event or a Temporal signal. No worker is involved: the step stays `PENDING` and the engine completes it when a `MessageReceived` event with the same `messageName` and a matching correlation key is published on the upstream surface (Kafka topic, embedded publisher, a [`SEND_MESSAGE` step](#send_message) in another process, the `sendMessage` MCP tool, or the REST endpoint below).

Correlate by business key:

```json
{
  "id": "wait-for-payment",
  "type": "WAIT_FOR_MESSAGE",
  "name": "Wait for payment confirmation",
  "messageName": "payment-received",
  "correlationExpression": "businessKey",
  "preconditionStepId": "send-invoice",
  "timeout": 259200000
}
```

Correlate by a process variable via a JEXL expression:

```json
{
  "id": "wait-for-payment",
  "type": "WAIT_FOR_MESSAGE",
  "name": "Wait for payment confirmation",
  "messageName": "payment-received",
  "correlationExpression": "orderId"
}
```

**Required fields:** `messageName`, `correlationExpression`

A message matches a waiting step when the message name is equal and the message's `correlationKey` equals the key the step expects: the value of `correlationExpression` — a JEXL expression evaluated against the process variables (same context and same fail-closed semantics as `preconditionExpression`; an expression that cannot be evaluated matches nothing). To correlate by business key, write `"correlationExpression": "businessKey"` explicitly. On match, the message's variables are merged into the process variables (visible to all successor steps) and the step completes.

Delivery semantics:

- **Not buffered.** A message that matches no waiting step is ignored (and logged). Upstream delivery is at-least-once, so the sender retries — or sends again once the process reaches the waiting step.
- **Idempotent.** A duplicate delivery of an already-correlated message is ignored: the step only completes once.
- **Broadcast.** If several processes are waiting on the same `messageName` and correlation key, all of them are resumed.

`timeout` and `retries` keep their usual meaning: a waiting WAIT_FOR_MESSAGE step that receives no message within `timeout` transitions to `TIMEOUT` and the normal retry/failure pipeline engages (a retry re-arms the wait).

Systems that cannot produce to Kafka (webhooks, SaaS callbacks) can deliver messages over REST — same correlation, any mode (embedded or kafka):

```bash
curl -X POST http://localhost:8080/workflow/api/messages \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: mysecret" \
  -d '{"messageName": "payment-received", "correlationKey": "res-123", "variables": {"paymentId": "P-9"}}'
```

The endpoint responds `202 Accepted` (fire-and-forget: an unmatched message is ignored, per the delivery semantics above). The `X-Api-Key` header is required only when `workflow.message-api.api-key` is configured:

```yaml
workflow:
  message-api:
    api-key: mysecret   # optional — leave unset to accept unauthenticated messages
```

:::note[Migration: this type was previously named `MESSAGE`]
`MESSAGE` was renamed to `WAIT_FOR_MESSAGE`. The old name is kept as a deserialization alias, so the persisted stepJson of in-flight processes and old definition files keep loading; for those legacy steps, a missing `correlationExpression` still falls back to matching the process `businessKey`. New or reimported definitions must use `WAIT_FOR_MESSAGE` and declare `correlationExpression` explicitly.
:::

---

## SEND_MESSAGE

The throw side of messaging: emits a `MessageReceived` event and completes immediately — the equivalent of a BPMN message throw event. No worker is involved. When the step starts, the engine evaluates `correlationExpression` (same JEXL context as `preconditionExpression`) to compute the correlation key, publishes `MessageReceived(messageName, correlationKey, variables)` through the outbox, and completes the step. Any process waiting on a [`WAIT_FOR_MESSAGE`](#wait_for_message) step with the same `messageName` and key resumes — so a workflow can signal another workflow without an `ACTION` step and a worker in between.

```json
{
  "id": "notify-payment",
  "type": "SEND_MESSAGE",
  "name": "Notify payment received",
  "messageName": "payment-received",
  "correlationExpression": "orderId",
  "messageVariables": ["paymentId", "amount"],
  "preconditionStepId": "charge-card"
}
```

**Required fields:** `messageName`, `correlationExpression`

**Optional fields:** `messageVariables` — an array of process-variable names selecting which variables the outgoing message carries. Empty or absent means the message carries no variables: process state is never sent implicitly.

Semantics:

- **Fire-and-forget.** Delivery is not acknowledged: the step completes when the message is emitted, not when someone receives it. A message that matches no waiting process is discarded (not buffered), per the delivery semantics above — sequence the two workflows so the receiver is already waiting, or have the sender retry.
- **Fail loud.** A missing `messageName` or `correlationExpression`, or a correlation expression that cannot be evaluated, transitions the step to `ERROR` and the normal retry/compensation pipeline engages. This is deliberately **not** the silent fail-closed behaviour of precondition guards: a message that silently goes nowhere would be much harder to diagnose.

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

:::caution[Not yet implemented by the engine]
`PROCESS` exists in the step-type enum and the JSON schema, but the engine has no dedicated handling for it: a `PROCESS` step is dispatched downstream as a plain `TaskExecutionRequested`, exactly like an `ACTION` step — no child process is started automatically. Until native support lands, start the child process from a worker (or an `EmbeddedTaskExecutor`) that consumes the step and sends a `ProcessCreationRequested`.
:::

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

:::caution[Not yet implemented by the engine]
`FORK` exists in the step-type enum and the JSON schema, but the engine has no dedicated handling for it: a `FORK` step is dispatched downstream as a plain `TaskExecutionRequested`, so it only completes if a worker acknowledges it. Parallelism does not come from `FORK` — it comes from marking the branch steps themselves `parallel: true` and pointing their `preconditionStepId` at the same predecessor, as in the example below (which works with or without the `FORK` marker step).
:::

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

:::caution[Not yet implemented by the engine]
`JOIN` exists in the step-type enum and the JSON schema, but the engine has no dedicated handling for it: a `JOIN` step is dispatched downstream as a plain `TaskExecutionRequested`, so it only completes if a worker acknowledges it. The join behaviour you usually want comes from the orchestration loop itself: a non-`parallel` step placed after a group of parallel steps does not start until no parallel step is still active, so a plain `ACTION` (or the `END` step) after the branch acts as the join point.
:::

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

An `END` step is recommended, but not enforced: the engine also completes a process **implicitly** when no runnable steps remain — no step is active, and every remaining step is blocked by a precondition that does not hold. On completion (explicit or implicit), any step still waiting is transitioned to `CANCELLED`. Declaring an explicit `END` makes the intended terminal point visible and lets you end the process early while other steps are still eligible.

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
| `compensationStepId` | string | — | Step to run as compensation. **Required when `rollbackable: true`** (enforced by the JSON schema) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on how many times this step may successfully run within one process instance (backstop against runaway loops). `0` inherits the workflow-level `defaultMaxStepExecutions`; both `0` = unbounded. Validated design metadata today — the engine runs each step once, and the cap will be enforced when step re-execution lands |
