---
title: Step Types
description: Reference for all step types available in EventConductor workflow definitions.
---

## Execution model: pure dataflow

Steps run by **data flow**, not by their position in the `steps` array. On every orchestration
tick, a step starts when all three conditions hold:

1. its execution is in `CREATED` status (it has not run yet),
2. **all** of its incoming links are satisfied — the links named in `preconditions`, or in
   `preconditionStepIds`, or the singular `preconditionStepId`,
3. its `preconditionExpression` (if any) evaluates truthy.

A link is satisfied when the step it names has `COMPLETED` **and** its own condition, if it
declares one, holds:

```json
{
  "id": "ship",
  "type": "ACTION",
  "preconditions": [
    { "stepId": "pack" },
    { "stepId": "charge", "expression": "amount > 100" }
  ]
}
```

The condition belongs to the link, so it says when *arriving by that route* counts. A link whose
condition is false is **not satisfied**, and a step that waits for all of its links waits — it is
not skipped and the process does not finish around it. Conditions are re-evaluated against the
process variables on every tick, so one that is false now holds the step until whatever it reads
changes.

:::caution[A guard that never becomes true is a process that never finishes]
That is the literal reading of "wait for all of them", and it is deliberate: the alternative —
quietly not requiring that branch — lets a step run having waited for less than its author wrote.
But it means a workflow can be authored into a permanent wait, and with nothing else in flight
there is nothing inside the engine left to change the variable. Such a process stays `RUNNING`
with the step in `CREATED`, where the `eventconductor.steps.stalled` gauge will not see it — that
gauge counts steps that started. Use `preconditionExpression`, which skips, when what you mean is
"maybe not this step"; use a link condition when what you mean is "only by this route".
:::

`preconditionExpression` is the older and different thing: it gates the **step**, however it is
reached, and a step it holds back is skipped so the process can finish (see
[Conditional skipping](/guides/retries-timeouts-compensation/#conditional-skipping)). Both still
work, and a definition may use either or both.

Every eligible step starts **concurrently**. There is no ordering between steps beyond the
precondition graph: an active step never blocks unrelated branches, and array order is
irrelevant. The `parallel` flag is **deprecated and ignored** (it still deserializes, so old
definition files keep loading) — parallelism is simply several steps declaring the same
precondition, and a barrier is one step declaring several preconditions.

Because eligibility is driven only by preconditions, **a step with no preconditions does not
run** — having nothing to wait for is not permission to start. The exceptions are the ways into
a flow, [`START`](#start) and a [`WAIT_FOR_MESSAGE`](#wait_for_message) that begins one, and
steps something else starts: a step named as another's `compensationStepId` is run by the
rollback pipeline and needs no precondition of its own. A step with no preconditions that is
none of those is rejected at load, because nothing would ever start it.

**Migrating an existing definition**: add one `START` step and point your old first steps at
it —

```json
{ "id": "start", "type": "START", "name": "Start" },
{ "id": "validate", "type": "ACTION", "name": "Validate", "topic": "order-validator",
  "preconditionStepId": "start" }
```

---

## START

Marks an entry point of the workflow. No worker is involved: the step completes instantly
when the process is created, which makes all its successors eligible. A `START` step must
**not** declare preconditions (rejected at load). Declaring **multiple START steps** gives the
process several entry branches that run concurrently from creation.

```json
{
  "id": "start",
  "type": "START",
  "name": "Start"
}
```

---

## ACTION

Dispatches a task to a worker microservice (or embedded bean). The workflow pauses until the worker reports completion.

```json
{
  "id": "process-payment",
  "type": "ACTION",
  "name": "Process Payment",
  "topic": "payment-service",
  "preconditionStepId": "start",
  "timeout": 30000,
  "retries": 3,
  "compensable": true,
  "compensationStepId": "refund-payment"
}
```

**Required fields:** none beyond `id`, `type` and `name`.

`topic` is **optional** and names the destination this step's task is dispatched to, which is how a
step is handed to a worker pool of its own. Omitted — the usual case — it means the shared
`downstream` destination, where every task goes unless told otherwise.

In **Kafka mode** the topic is the binding the task is sent on. A topic with no binding of its own
is a dynamic destination: Spring Cloud Stream creates it on first use, so naming one needs no
configuration in the application. Whatever consumes that topic must, of course, exist — a task sent
where nobody is listening is not refused by anything, and shows up only as a step that sits until
its `timeout`.

In **embedded mode** the field is ignored: there is one in-process `EmbeddedTaskExecutor` and no
transport to route over, so it takes every ACTION step whatever the topic says.

A step's cancellation (`TaskCancellationRequested`, sent when a process is cancelled, stepped over
or a task times out) is addressed to the **same** topic the task was dispatched to, so a worker pool
of its own still hears about work it should stop.

:::caution[It does not compose with sharding on its own]
A shard suffixes its destinations through binding properties —
`spring.cloud.stream.bindings.downstream.destination=downstream-<shard>`. A step's `topic` is used
as the **binding name**, so a topic with no binding of its own becomes a dynamic destination named
exactly what the step says, with no suffix: every shard's tasks for that step land on one topic.

Sharded deployments therefore either leave `topic` unset, so tasks go through the shard-mapped
`downstream` binding, or declare a binding per topic per shard
(`spring.cloud.stream.bindings.<topic>.destination=<topic>-<shard>`) on the orchestrator and point
that shard's workers at the same name.
:::

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
  "untilVariable": "checkinDate",
  "preconditionStepId": "confirm-booking"
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

Starts a **child workflow** as a sub-process. No worker is involved: when the step starts, the
engine creates a process of `childWorkflowDefinitionId` carrying **all** the parent's process
variables, and the parent step waits `PENDING` until the child reaches a terminal status.

```json
{
  "id": "run-kyc",
  "type": "PROCESS",
  "name": "Run KYC Check",
  "childWorkflowDefinitionId": "kyc-workflow",
  "outputVariables": ["kycResult", "kycScore"],
  "preconditionStepId": "create-customer",
  "timeout": "PT1H"
}
```

**Required fields:** `childWorkflowDefinitionId` — must be different from the workflow's own
id (direct self-recursion is rejected at load).

**Optional fields:** `outputVariables` — an array of child-process variable names copied back
into the parent when the child completes. Empty or absent means **none**: child state is never
merged back implicitly.

Semantics:

- The child process is created with the deterministic business key
  **`parent:<stepExecutionId>`**, which makes creation **idempotent** — a redelivered creation
  event is deduplicated by business key, so at-least-once delivery never spawns a second child.
- When the child process reaches `COMPLETED`, the parent step completes and only the child
  variables named in `outputVariables` are copied into the parent process.
- When the child ends `ERROR` or `CANCELLED`, the parent step transitions to `ERROR` and the
  normal retry/compensation pipeline engages.
- `timeout` bounds the wait: a parent step whose child has not finished within `timeout` goes
  through the usual `TIMEOUT` → retry/`ERROR` pipeline.

Cancellation propagates from parent to child: when the parent `PROCESS` step ends `CANCELLED`,
`ERROR` or `TIMEOUT` (retries exhausted), a still-running child process is cancelled too — and
the cascade continues to grandchildren. While retries remain, the child is left running: a
retried `PROCESS` step re-attaches to the same child through the deterministic business key.

---

## FORK

Fans a flow out into parallel branches. No worker is involved: the step completes instantly
when it starts, which makes **all** its successors eligible at once — every step whose
precondition is the FORK starts concurrently.

`FORK` is readability sugar: the fan-out actually comes from several steps declaring the same
precondition (which works with any step type as the predecessor). Use a `FORK` node when you
want the branching point to be explicit in the definition and the graph.

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
  "preconditionStepId": "fork-notifications"
},
{
  "id": "send-sms",
  "type": "ACTION",
  "name": "Send SMS",
  "topic": "sms-service",
  "preconditionStepId": "fork-notifications"
}
```

---

## JOIN

The converge point of parallel branches. No worker is involved — once its preconditions are met,
the step completes instantly and the flow continues after it. Its `joinType` chooses the merge:

- **`AND`** (default) — a synchronizing join: waits until **all** the steps in its
  `preconditionStepIds` have `COMPLETED`.
- **`XOR`** — an exclusive join: proceeds as soon as **any one** incoming branch completes.

```json
{
  "id": "join-notifications",
  "type": "JOIN",
  "name": "All notifications sent",
  "joinType": "AND",
  "preconditionStepIds": ["send-email", "send-sms"]
}
```

The barrier **is** the multiple preconditions: a JOIN with a single precondition waits for
nothing extra. (Any step may declare several preconditions — a `JOIN` node just makes the
convergence explicit, exactly as `FORK` makes the fan-out explicit.) `joinType` is `AND` unless
set to `XOR`; it is ignored on non-JOIN steps.

:::caution[JOIN on a guarded branch]
If a branch feeding the JOIN carries a `preconditionExpression` that evaluates false, that
branch never runs — so the JOIN never fires, and implicit completion cancels the JOIN and
everything after it while the process still completes successfully. The
[Maven plugin](/reference/maven-plugin/) emits a build-time warning when a JOIN waits directly
on a guarded step.
:::

---

## CHOICE

An **exclusive split**: the branch counterpart of the `XOR` join. Where a `FORK` takes *every*
eligible successor, a `CHOICE` takes exactly **one** — the first whose guard holds. No worker is
involved; like `FORK` and `JOIN` it completes instantly when it starts, and the decision is made
from the guards on its outgoing links.

The branches are evaluated **from the longest guard expression to the shortest** — most specific
first, down to the most general — and the **first** one that holds is taken; the rest are
discarded. A successor whose link carries **no guard** is the default (`else`): being the shortest,
it is tried last, and it always holds, so it is taken only when nothing more specific did. Guards
live on the link (a `preconditions` entry with an `expression`), so a `CHOICE` reads the same
per-route conditions the graph shows.

```json
{
  "id": "route",
  "type": "CHOICE",
  "name": "Route by customer tier",
  "preconditionStepId": "score-customer"
},
{
  "id": "handle-vip",
  "type": "ACTION",
  "name": "White-glove path",
  "topic": "vip-service",
  "preconditions": [{ "stepId": "route", "expression": "tier == 'gold' && region == 'EU'" }]
},
{
  "id": "handle-priority",
  "type": "ACTION",
  "name": "Priority path",
  "topic": "priority-service",
  "preconditions": [{ "stepId": "route", "expression": "tier == 'gold'" }]
},
{
  "id": "handle-standard",
  "type": "ACTION",
  "name": "Standard path",
  "topic": "standard-service",
  "preconditions": [{ "stepId": "route" }]
}
```

Here a `gold`/`EU` customer takes `handle-vip` (longest guard, evaluated first); any other `gold`
customer takes `handle-priority`; everyone else falls through to the unguarded `handle-standard`.

The pick **latches**: once a branch has started, a later change to the variables a guard reads
cannot hand the split to a different branch. The `CHOICE` decides when it completes and does not
wait — unlike an ordinary guarded link, a branch it did not take is *discarded*, not held.

**Converge exclusive branches with an `XOR` [`JOIN`](#join), not an `AND` join.** This is the
split↔join pairing — `FORK` with an `AND` join, `CHOICE` with an `XOR` join. A `CHOICE` runs only
one branch, so an `AND` join downstream would wait for the branches that never ran and **never
fire**; the process then completes implicitly, cancelling the join and everything after it (the
same mechanism as [*JOIN on a guarded branch*](#join) above). An `XOR` join proceeds on the one
branch that did run.

:::caution[No default branch]
If every guard is false at runtime and there is **no** unguarded default, a `CHOICE` takes **no**
branch at all — a valid but easily-unintended dead end. The engine's topology validation and the
[Maven plugin](/reference/maven-plugin/) warn when a `CHOICE` has no default branch. Ties on guard
length are broken deterministically by step id.
:::

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

## DYNAMIC

Dispatches a task to a worker like an [`ACTION`](#action), but the worker's reply may **inject new steps into the running process** — the steps, and their preconditions, that the worker decided are needed at runtime. This is how a workflow decides part of its own shape: a fan-out whose width is only known once the work starts, a plan a worker computes and then executes.

```json
{
  "id": "plan",
  "type": "DYNAMIC",
  "name": "Plan the work",
  "topic": "work",
  "preconditionStepId": "start"
}
```

**Required fields:** none beyond `id`, `type` and `name`. `topic` is optional and routes the task — same as `ACTION`.

The worker injects with `WorkerReply.inject(...)` / `injectAndComplete(...)` (message `StepsInjected`). Injection is **add-only**, the worker supplies each step's own preconditions (there is no default wiring — an unreachable injected step is a visible bug, not something the engine fixes up), and the engine validates the whole batch (unique ids, resolved references, no cycle, within the [step budget](/guides/dynamic-workflows/#runaway-guards)) and **fails the `DYNAMIC` step** if it is rejected. Only a `DYNAMIC` step may inject. See [Dynamic Workflows](/guides/dynamic-workflows/) for the full picture.

---

## Common fields (all step types)

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique identifier within the workflow |
| `type` | enum | — | Step type (see above) |
| `name` | string | — | Human-readable name |
| `description` | string | — | Optional description |
| `preconditionStepId` | string | — | Single step that must complete before this one starts |
| `preconditionStepIds` | string[] | — | Steps that must **all** complete before this one starts; takes precedence over the singular `preconditionStepId` when non-empty |
| `preconditionExpression` | string | — | JEXL expression; step skipped if evaluates to `false` |
| `parallel` | boolean | `false` | **Deprecated and ignored** — every eligible step runs concurrently; kept only so old files keep deserializing |
| `outputVariables` | string[] | — | PROCESS only: child variables copied back into the parent on completion; empty/absent = none |
| `timeout` | integer (ms) | `0` | Max execution time; `0` = no timeout |
| `retries` | integer | `0` | Auto-retry attempts on ERROR or TIMEOUT |
| `compensable` | boolean | `false` | Trigger compensation step on failure |
| `compensationStepId` | string | — | Step to run as compensation. **Required when `compensable: true`** (enforced by the JSON schema) |
| `onTimeoutStepId` | string | — | Step to route to when this step times out (after `retries` are exhausted) instead of failing the process — the step's own on-timeout branch. See [On-timeout routing](/guides/retries-timeouts-compensation/#on-timeout-routing) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on how many times this step may successfully run within one process instance (backstop against runaway loops). `0` inherits the workflow-level `defaultMaxStepExecutions`; both `0` = unbounded. Validated design metadata today — the engine runs each step once, and the cap will be enforced when step re-execution lands |
