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
condition is false is **not satisfied**, and by default a step that waits for all of its links
waits — it is not skipped and the process does not finish around it. Conditions are re-evaluated
against the process variables on every tick, so one that is false now holds the step until
whatever it reads changes.

### What a false condition means: `onFalse`

Waiting is one of two things a false condition can mean, and the link says which:

| `onFalse` | A false condition means | The step |
|---|---|---|
| `WAIT` (default) | *not yet* — the variables may still change | waits, and the process stays open around it |
| `DISCARD` | *not this way* — the flow did not come by this route | is a branch not taken; the process may finish and cancel it |

```json
{
  "id": "charge-penalty",
  "type": "ACTION",
  "preconditions": [
    { "stepId": "validate", "expression": "ratePlan == 'NON_REFUNDABLE'", "onFalse": "DISCARD" }
  ]
}
```

Reach for `DISCARD` on an optional or exclusive branch — the step that only some processes go
through — and leave the default on a condition that is genuinely a wait, such as a link into a
step that resumes once a worker or an operator sets the variable it reads. Getting it wrong is
visible in opposite ways: a `WAIT` that was meant as a branch leaves the process `RUNNING`
forever, and a `DISCARD` that was meant as a wait lets the process finish without the step.

:::caution[A `WAIT` guard that never becomes true is a process that never finishes]
That is the literal reading of "wait for all of them", and it is deliberate: the alternative —
quietly not requiring that branch — lets a step run having waited for less than its author wrote.
But it means a workflow can be authored into a permanent wait, and with nothing else in flight
there is nothing inside the engine left to change the variable. Such a process stays `RUNNING`
with the step in `CREATED`, where the `eventconductor.steps.stalled` gauge will not see it — that
gauge counts steps that started. If what you mean is "maybe not this step", say `onFalse:
"DISCARD"`.
:::

`preconditionExpression` is the older spelling. It gates the **step**, however it is reached, and
the engine reads it by folding it into every one of the step's links: "this step only runs if X"
is "every route into it requires X", so there is one kind of condition and one place that
evaluates it. What it *means* is folded in too: it becomes an `onFalse: "DISCARD"` guard, which is
what a step-level expression has always done — a step it holds back is **skipped**, so the process
can finish around it (see
[Conditional skipping](/guides/retries-timeouts-compensation/#conditional-skipping)). Both
spellings still work and a definition may use either or both; prefer the link, which can say
something different of each route and, with `onFalse`, say what a false condition means. A step
with no links at all — an entry point — has nothing to fold into, and there the expression stays
its own gate.

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
**not** declare preconditions (rejected at load). A workflow has **at most one START** (a second
one is rejected at load); for several branches that run concurrently from creation, give the START
several successors or follow it with a `FORK`.

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

`task` is **optional** and names the [task contract](/reference/versioning/#task-contracts) this step
runs, as `<id>` or `<id>@<version>`. A bare id is **pinned to the contract's latest version when the
definition is imported**, so the reference the worker receives (as the task's `taskId`) is always
fully qualified and a definition never changes contract without someone editing the file. The
contract's own `topic` becomes this step's default destination — so with a contract you usually need
no `topic` here — and an explicit `topic` still overrides it. A step with no `task` dispatches an
empty `taskId`, exactly as every ACTION did before task contracts.

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

**Optional fields:**

| Field | Type | Description |
|---|---|---|
| `assignment` | object | Who the task is for and how urgent it is, rendered from the process (an iteration's item too) when the task is created: `{assignee?, candidateUsers?, candidateGroups?, priority?, dueDate?, followUpDate?}`. Every value may be a `${…}` template. `priority` is 0–100 (default 50); `dueDate`/`followUpDate` are an ISO-8601 duration from the task's creation (`P3D`), an ISO date or date-time, or a template giving either. A task that names an assignee or candidates is visible and claimable only by them and by task admins |
| `escalation` | object[] | What happens while the task stays open, each entry `after` an ISO-8601 duration from its creation, listed in the order they fire: `{after, action?, to?, escalateTo?, publishEvent?}`. `action` is `reassign` (the task is for `to` from now on), `notify` (recorded and announced, nothing changes) or `escalateTo` (the task is closed and the process goes on at the step named in `escalateTo`, as `onTimeoutStepId` routes a timed-out step — last entry only, not on a `forEach` step). Any entry may `publishEvent` (the shape of a PUBLISH_EVENT's `event`). Not combined with `deadline` |

```yaml
- id: approve
  type: USER_TASK
  name: Approve the expense
  formId: expense-approval
  preconditionStepId: register
  timeout: P10D
  assignment:
    assignee: "${manager}"
    candidateGroups: ["finance-${region}"]
    priority: "${amount > 1000 ? 90 : 40}"
    dueDate: P2D
  escalation:
    - after: PT48H
      action: reassign
      to: { candidateGroups: [finance-managers] }
    - after: P5D
      escalateTo: director-review
```

See [Human tasks: assignment and escalation](/guides/human-tasks-assignment-and-escalation/).

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

Wait for a moment computed from the process's data — three days before check-in, at 09:00 hotel time — which follows the data if it changes while the timer waits (see [Scheduling and Deadlines](/guides/scheduling-and-deadlines/)):

```json
{ "id": "wait-balance", "type": "TIMER", "name": "3 days before check-in", "preconditionStepId": "confirm-booking",
  "until": { "date": "${checkinDate}", "offset": "-P3D", "at": "09:00", "zone": "${hotelZone}", "ifPast": "fire" } }
```

**Required fields:** one of `duration`, `untilVariable` or `until`

`until` is a moment: `{date, offset, at, zone, ifPast}`, or a date template alone. `ifPast` (`fire` default, or `timeout` — which follows `onTimeoutStepId` or fails) decides what a moment already past does.

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

**Fed by a domain event.** With an `onEvent` block, a CloudEvent arriving on a destination becomes the step's message — no one has to call the message API:

```yaml
- id: wait-payment
  type: WAIT_FOR_MESSAGE
  name: Wait for the payment
  messageName: payment-captured
  correlationExpression: bookingId
  onEvent:
    destination: payments                # workflow.events.destinations.payments.topic
    type: com.acme.payment.captured      # '*' wildcards allowed
    correlationKey: "${data.bookingId}"  # template over the event; default the record key, else the subject
    filter: "data.status == 'CAPTURED'"  # optional JEXL over the event
    variables: { paymentId: "${data.paymentId}" }   # default: data's top-level members
```

The same event (CloudEvents source + id) is correlated at most once per message and key. See [Starting on and waiting for domain events](/guides/domain-event-triggers/).

`timeout` and `retries` keep their usual meaning: a waiting WAIT_FOR_MESSAGE step that receives no message within `timeout` transitions to `TIMEOUT` and the normal retry/failure pipeline engages (a retry re-arms the wait).

Systems that cannot produce to Kafka (webhooks, SaaS callbacks) can deliver messages over REST — same correlation, any mode (embedded or kafka):

```bash
curl -X POST http://localhost:8080/workflow/api/messages \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: mysecret" \
  -d '{"messageName": "payment-received", "correlationKey": "res-123", "variables": {"paymentId": "P-9"}}'
```

The endpoint responds `202 Accepted` (fire-and-forget: an unmatched message is ignored, per the delivery semantics above). Since 2.27.0 the engine's REST API (`/workflow/api/**`) refuses anonymous requests: send an `X-Api-Key` matching `workflow.message-api.api-key`, or authenticate through the application's Spring Security; only `workflow.message-api.allow-anonymous=true` opens it — see [HTTP security](/reference/configuration/#http-security).:

```yaml
workflow:
  message-api:
    api-key: mysecret   # sent as X-Api-Key; without it (and without Spring Security) the API answers 401
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
- **Retries run the child again.** With `retries` set, a step whose child ended `ERROR` or
  `CANCELLED` is retried by starting a **fresh** child process, under the next deterministic key:
  `parent:<stepExecutionId>#2`, then `#3`, and so on. The failed child keeps its own key, status
  and history. The same goes for an operator retry of the step, or a restart of the parent process.
  A retry after a `timeout` is different: the child is still running, so the retried step
  **re-attaches** to it and does not start a second one.

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

:::note[Only `AND` and `XOR` — no *n-of-m* join]
The merge is a two-valued choice: wait for **all** branches (`AND`) or the **first** one (`XOR`).
There is deliberately no first-class *n-of-m* (partial join / discriminator) — "proceed once **2
of 3** branches complete". You can approximate one with a guard that counts completed branches out
of a process variable, but that count then lives inside a JEXL expression rather than a declared
field, so the graph no longer shows the real merge condition — losing the one property this DSL
otherwise keeps: the derived graph never says less than the definition means. That is why it is
left out rather than papered over. If a genuine quorum requirement appears (n-of-m approvals, a
majority of redundant calls), the intended shape is a **declared threshold on the JOIN** — a
first-class `count`/`quorum` field the renderer can show — not hand-rolled counting. Until then,
model the quorum explicitly with the branches you have.
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

## LOCK

Acquire a named per-key lock, serializing every process — or every critical section — that targets the same key: for example, all the processes touching one reservation. No worker is involved. The step completes the moment the process holds the lock; if another process holds it, this one parks the step in `WAITING_ON_LOCK` and is admitted **FIFO**, in arrival order, when the lock frees.

```json
{
  "id": "lock-booking",
  "type": "LOCK",
  "name": "Lock the booking",
  "lockName": "booking",
  "lockKey": "bookingId",
  "preconditionStepId": "start"
}
```

**Required fields:** `lockKey` — a JEXL expression over the process variables giving the key that is locked (e.g. `bookingId`); a definition with a `LOCK` or `UNLOCK` step without one is rejected at load. It is evaluated with the same context as `correlationExpression` (the variables, plus `businessKey` and `process`); a key that cannot be evaluated fails the step (`ERROR`).

**Optional fields:** `lockName` — the lock domain (e.g. `"booking"`). Together with the key it names the lock: every step and process sharing the `(lockName, key)` pair is serialized against each other. Defaults to the definition's id, so give one name to steps of different definitions that must serialize together.

Semantics:

- **Reentrant.** A process taking a lock it already holds gets it again at once.
- **Released** by a matching [`UNLOCK`](#unlock) step, or — whatever happened in between — when the process reaches a terminal state (`COMPLETED`, `ERROR`, `CANCELLED`, `COMPENSATED` or `COMPENSATION_FAILED`), so a process that fails, or is cancelled, inside its critical section does not wedge the key. A failed process that has compensations to run keeps its locks until the rollback ends (`COMPENSATED` or `COMPENSATION_FAILED`): what the lock protects is still being undone, so the next process on the key only starts once it is. A process that ends while queued for a lock leaves the queue. A lease (`workflow.lock.lease-ms`) is the backstop for a pod that dies while holding one; see [Serialization locks](/reference/configuration/#serialization-locks-workflowlock).
- **Always waits.** A `LOCK` step never refuses; to refuse a synchronous caller when the key is busy, use a process-level lock with `onLockBusy: FAIL` (below).

**Process-level lock.** To serialize *whole instances* of a definition rather than a section of them, declare `processLock` on the definition instead of steps: `"processLock": { "name": "booking", "key": "bookingId" }` (`key` required, `name` defaulting to the definition id). An instance that cannot take it makes no progress at all until it is admitted, and holds it until it ends. See [Synchronous Invocation — Locks](/guides/synchronous-invocation/#6-locks) for `onLockBusy`.

---

## UNLOCK

Release a lock taken by a [`LOCK`](#lock) step, admitting the next waiter for that key. No worker; completes immediately.

```json
{
  "id": "unlock-booking",
  "type": "UNLOCK",
  "name": "Release the booking",
  "lockName": "booking",
  "lockKey": "bookingId",
  "preconditionStepId": "update-booking"
}
```

**Required fields:** `lockKey`, as for `LOCK`. **Optional fields:** `lockName` (default: the definition's id). Name the same pair as the `LOCK` it closes. Releasing a lock this process does not hold is a no-op. An `UNLOCK` is not required for correctness — the process releases everything it holds when it ends — but without one the key stays locked for the rest of the process.

---

## REPLY

The answer a process gives to whoever invoked it [synchronously](/guides/synchronous-invocation/). No worker: the step completes in the step-over that reaches it, recording the reply on the process in the same transaction, and the flow carries on. Before `END` it answers with the result; earlier, the caller gets its answer while the process continues.

```json
{
  "id": "confirm",
  "type": "REPLY",
  "name": "Booking confirmed",
  "replyVariables": ["bookingId", "confirmation"],
  "preconditionStepId": "charge"
}
```

**Optional fields** (at most one of the three): `replyVariables` — the reply is a JSON object with one member per listed variable; `replyExpression` — a JEXL expression whose value is the reply (e.g. `{'bookingId': bookingId, 'status': 'CONFIRMED'}`); `replyTemplate` — a [payload template](/guides/payload-templates/). Neither → `{}`.

Semantics:

- **At most one per run**, checked on import and by the Maven plugin: two `REPLY` steps must be separated by an exclusive split (different `CHOICE` branches, or a step's normal vs `onTimeoutStepId` route) that every way into both goes through. A `REPLY` cannot be a compensation step or be injected by a `DYNAMIC` step. At runtime the first reply stands.
- **Fail loud.** A reply that cannot be computed, or is larger than `workflow.sync.max-reply-bytes`, fails the step (`ERROR`), so the failure contract answers the caller instead.
- A process started asynchronously still records its reply.

---

## PUBLISH_EVENT

Publish a domain event from the process's state, without a worker. See [Publishing Domain Events](/guides/publishing-events/).

```json
{ "id": "announce", "type": "PUBLISH_EVENT", "name": "Booking confirmed", "preconditionStepId": "confirm",
  "event": { "destination": "bookings", "type": "com.acme.booking.confirmed", "key": "${bookingId}",
             "payload": { "bookingId": "${bookingId}" } } }
```

**Required:** `event.destination` (a logical name mapped to a topic by `workflow.events.destinations`), `event.type`. **Optional:** `event.key` (template; default the business key, else the process id), one of `event.payload` (structured template) / `event.payloadTemplate` (text) / `event.payloadVariables`, `event.format` (`binary` default, `structured`, `plain`).

Written to the outbox with the step's completion — published if and only if the step completed; at-least-once, `id` = the step execution id. An unknown destination, a template that does not render or an oversized payload fails the step.

**Waiting for the answer: `awaitReply`.** With an `awaitReply` block the step is a request that waits for its reply — asynchronous request/response in one step, with no reply lost however fast it comes back:

```yaml
- id: ask-quote
  type: PUBLISH_EVENT
  name: Ask for a quote
  preconditionStepId: start
  timeout: PT5M                       # bounds request + reply
  retries: 0
  onTimeoutStepId: no-quote
  event: { destination: quote-requests, type: com.acme.quote.requested, key: "${process.businessKey}",
           payload: { requestId: "${process.businessKey}", item: "${item}" } }
  awaitReply:
    messageName: quote-ready          # the message that is the reply
    correlationExpression: businessKey
    onEvent:                          # optional: the reply comes as a domain event
      destination: quote-replies
      type: com.acme.quote.ready
      correlationKey: "${data.requestId}"
      variables: { price: "${data.price}" }
```

`awaitReply` takes the fields of a [`WAIT_FOR_MESSAGE`](#wait_for_message) — `messageName` and `correlationExpression` (both required) and an optional `onEvent` — with the same meaning. Written on the step itself, outside `awaitReply`, `messageName` and `correlationExpression` are ignored (as they always were) and the import and the Maven plugin warn about them. The wait is armed in the transaction that writes the event to the outbox, so the event cannot leave before the step is waiting for its answer. The step stays `PENDING` after publishing and completes when the reply is correlated, its variables merged into the process. `timeout`, `deadline`, `retries` and `onTimeoutStepId` bound the whole exchange; a retry publishes the request again — as a new event, `id` = `<step execution id>-<attempt>` — and waits again. See [Asynchronous Request/Response](/guides/request-response/#b-ec-asks-and-waits).

---

## HTTP_CALL

Call an HTTP endpoint and map the response into variables, without writing a worker. See [HTTP Calls](/guides/http-calls/).

```json
{ "id": "charge", "type": "HTTP_CALL", "name": "Charge", "preconditionStepId": "book", "retries": 2,
  "http": { "connection": "payments", "method": "POST", "path": "/charges/${bookingId}",
            "body": { "amount": "${amount * 1}" }, "output": { "chargeId": "body.id" } } }
```

**Required:** `http` with exactly one of `connection` (+ optional `path`) and `url`. **Optional:** `method` (default `GET`), `query`, `headers`, `body` / `bodyTemplate`, `successStatus` (default 2xx), `output` (variable ← JEXL over `{status, headers, body}`), `auth` (a profile name, or inline with `${secret:NAME}` credentials only), `retryOn` (default `[5xx, io]` — a 4xx is not retried).

Rendered by the engine and executed by the built-in `http-call@1` task (`worker-http`): embedded in the engine's pod, or on topic `http-calls` in kafka mode. Timeouts, retries, compensation and cancellation work as for `ACTION`.

**Waiting for a callback: `awaitReply`.** When the HTTP response only acknowledges the request and the answer comes later — a webhook, a callback, an event — give the step an `awaitReply` (the fields of a [`WAIT_FOR_MESSAGE`](#wait_for_message), as on [`PUBLISH_EVENT`](#publish_event)):

```yaml
- id: ask-quote
  type: HTTP_CALL
  name: Ask for a quote
  preconditionStepId: start
  timeout: PT5M
  onTimeoutStepId: no-quote
  http: { connection: pricing, method: POST, path: /quotes,
          body: { requestId: "${process.businessKey}", item: "${item}" }, output: { ticket: "body.ticket" } }
  awaitReply: { messageName: quote-ready, correlationExpression: businessKey }
```

The wait is armed before the HTTP task is dispatched, so a callback the remote system sends before (or instead of waiting for) its own response is never lost. The step completes when the reply is correlated:

- **Response first, then reply**: the 2xx response is recorded — `output` mapped into the process — and the step keeps waiting (`PENDING`, under the same `timeout`). The reply completes it.
- **Reply first, then response**: the reply completes the step; the response that comes after finds it complete and is ignored, `output` included. Do not rely on `output` in successors: correlate on an id you send, and carry what you need in the reply.
- **A failed request** (non-2xx, I/O error, before any reply) fails the step as any `HTTP_CALL` fails: `retries` send the request again — with the same `Idempotency-Key` — and re-arm the wait. A reply that arrives while the step waits out its backoff matches nothing.
- The worker's `RUNNING` progress report leaves the step `PENDING`: `timeout` bounds the request and the reply together — and `workflow.default-step-timeout-ms`, when set, applies to an `HTTP_CALL` without a `timeout` whether or not it awaits a reply, so give one that awaits a slow reply a `timeout` of its own.

---

## LLM_CALL

Ask a language model a question built from the process's data and map its answer — text, or JSON valid against a schema — into variables, without writing a worker. See [LLM Calls](/guides/llm-calls/).

```json
{ "id": "triage", "type": "LLM_CALL", "name": "Triage", "preconditionStepId": "log", "retries": 2,
  "llm": { "connection": "claude", "system": "You triage hotel guest complaints.",
           "prompt": "${guestName} wrote: ${complaint}",
           "responseSchema": { "type": "object", "required": ["category"],
                               "properties": { "category": { "enum": ["room", "billing", "staff", "other"] } } },
           "output": { "category": "json.category" }, "usageVariable": "triageUsage" } }
```

**Required:** `llm` with `connection` (a `workflow.llm.connections.<name>`), `prompt` (text template), and one of `output` / `outputVariable`. **Optional:** `model` (overrides the connection's), `system` (text template), `temperature` (0–2, sent only when set), `maxTokens`, `responseSchema` (JSON Schema — the answer is parsed and validated; an invalid one fails with `LLM_INVALID_OUTPUT`, retried), `output` (variable ← JEXL over `{text, json, usage, model, finishReason}`), `outputVariable` (the text, or the JSON document), `usageVariable` (`{model, inputTokens, outputTokens, totalTokens}`). No key or `${secret:…}` in the block: keys are the connection's configuration.

Rendered by the engine and executed by the built-in `llm-call@1` task (`worker-llm`, on Spring AI: Anthropic and OpenAI-compatible providers): embedded in the engine's pod, or on topic `llm-calls` in kafka mode. Timeouts, retries, compensation and cancellation work as for `ACTION`; a provider 4xx (bad request, bad key) is final, 429/5xx/timeouts are retried.

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
| `deadline` | moment | — | ACTION, USER_TASK, RULE, WAIT_FOR_MESSAGE, PROCESS, HTTP_CALL, LLM_CALL, and a PUBLISH_EVENT with `awaitReply`: a moment (`{date, offset, at, zone}` from the process's data) by which the step must have finished. Reaching it is a timeout that retries do not extend; with `timeout` too, the earlier wins; it follows the variables. See [Scheduling and Deadlines](/guides/scheduling-and-deadlines/) |
| `awaitReply` | object | — | HTTP_CALL and PUBLISH_EVENT only: `{messageName, correlationExpression, onEvent?}` — the reply the step waits for after sending its request, armed in the same transaction that sends it. See [PUBLISH_EVENT](#publish_event) and [HTTP_CALL](#http_call) |
| `onEvent` | object | — | WAIT_FOR_MESSAGE only: `{destination, type, correlationKey?, filter?, variables?}` — a domain event that becomes the step's message. See [Starting on and waiting for domain events](/guides/domain-event-triggers/) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on how many times this step may successfully run within one process instance (backstop against runaway loops). `0` inherits the workflow-level `defaultMaxStepExecutions`; both `0` = only the loop's own `maxIterations` and `workflow.loops.max-iterations` bound it. Enforced when a `repeat` or `loopBack` would run the step again: the step fails instead |
| `forEach` | object | — | ACTION, USER_TASK, PROCESS, HTTP_CALL, LLM_CALL, RULE: run the step once per item — `{items, as?, indexAs?, mode?, maxConcurrency?, output?, outputElement?, completion?}`. See [Loops and Multi-Instance](/guides/loops-and-multi-instance/#foreach--once-per-item) |
| `repeat` | object | — | Same types: run the step again until a condition holds — `{until, maxIterations, delay?}`. See [repeat](/guides/loops-and-multi-instance/#repeat--one-step-until-a-condition) |
| `loopBack` | object | — | Any step but START/END/REPLY: when it completes and `while` holds, run every step from `to` to here again — `{to, while?, maxIterations, delay?}`. See [loopBack](/guides/loops-and-multi-instance/#loopback--run-a-section-again) |
