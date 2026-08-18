---
title: Workflow Definitions
description: The EventConductor workflow DSL — steps, branching, retries, and more. Supports JSON and YAML.
---

Workflow definitions describe the steps of a business process. They can be written in **JSON** or **YAML** (`.json`, `.yaml`, `.yml`), are version-controlled, and are reviewable in a pull request.

In `embedded` + `memory` mode, definitions are loaded from `classpath:/workflows/` at startup. In `jpa` persistence mode, they can also be imported from Git — either at startup, on demand via the MCP tool `importWorkflowDefinitionsFromGit`, or automatically via a **git webhook** (GitHub, GitLab, Bitbucket or generic).

## IDE support

Add a `$schema` reference to your JSON files to enable autocomplete and inline validation in IntelliJ, VS Code, and any other JSON Schema-aware editor:

```json
{
  "$schema": "https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json",
  "id": "my-workflow",
  ...
}
```

For YAML files, add the `yaml-language-server` comment at the top of the file (supported by VS Code's YAML extension and IntelliJ 2023.1+):

```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json
id: my-workflow
...
```

As a fallback, configure the schema association manually in your editor (IntelliJ: *Preferences → Languages & Frameworks → Schemas and DTDs → JSON Schema Mappings*).

## File format

Both formats are fully equivalent — use whichever fits your team's conventions.

**JSON:**

```json
{
  "id": "my-workflow",
  "name": "My Workflow",
  "version": 1,
  "description": "Optional description",
  "limitConcurrentExecutions": false,
  "maxConcurrentExecutions": 0,
  "enqueueOnLimit": false,
  "steps": [...]
}
```

**YAML:**

```yaml
id: my-workflow
name: My Workflow
version: 1
description: Optional description
limitConcurrentExecutions: false
maxConcurrentExecutions: 0
enqueueOnLimit: false
steps: [...]
```

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique workflow identifier |
| `name` | string | Human-readable name |
| `version` | integer | **Ignored for numbering.** The engine assigns versions itself on each content change (see [Versioning](#versioning)); any value here is not authoritative and is overwritten |
| `description` | string | Optional description |
| `paused` | boolean | Runtime flag — **not authored in the `.ec`**. Toggled at runtime (UI, `PauseWorkflowUseCase`/`ResumeWorkflowUseCase`, MCP). While `true`, all the definition's processes are held and new instances (cron included) are created born-`PAUSED`. Kept in the schema (default `false`) only so exported definitions round-trip |
| `status` | string | `ACTIVE` (default), `DISABLED` or `ARCHIVED`. **Authored in the `.ec`**, and a floor: an operator can take a workflow out of service at runtime, but cannot put one into service that its own definition closes. This is how a definition lives in the repository without being live |
| `disabled`, `archived` | boolean | The older way of saying the same thing, still read: `disabled: true` means `status: DISABLED`, `archived: true` means `status: ARCHIVED` |
| `limitConcurrentExecutions` | boolean | Cap concurrent running instances |
| `maxConcurrentExecutions` | integer | Max instances (if limit enabled) |
| `enqueueOnLimit` | boolean | Queue new instances when limit reached |
| `cronExpression` | string | Spring cron expression (six fields, seconds first). Unless the definition is disabled or archived, the engine creates a process instance at each occurrence. `null` = no scheduled starts |
| `defaultMaxStepExecutions` | integer | Default cap on how many times each step may successfully run within one process instance; a step's own `maxSuccessfulExecutions` overrides it. `0` or `null` = unbounded |
| `maxSteps` | integer | Cap on the total number of step executions one process instance may ever hold, runtime injections included (see [Dynamic Workflows](/guides/dynamic-workflows/)). `0` or `null` falls back to the engine-wide `workflow.dynamic.max-steps-per-process` default; any positive value overrides it for this definition's processes |

### Runtime state

Definitions are **authored as `.ec` files** (edited with the [IDE plugins](/guides/ide-plugins/))
and imported — they are never edited in the management UI. The UI toggles runtime state:

| Action | Effect |
|---|---|
| **Pause / Resume** | While paused, the definition's processes are held and new instances (cron included) are created born-`PAUSED`; resume moves everything on. |
| **Disable / Enable** | While disabled, the definition accepts **no new instances** (cron included); already-running instances are unaffected. |

A definition removed from its Git repository is **archived** by the import prune (hidden, not
deleted) — see [Importing from Git](#importing-from-git). Archived and disabled definitions do not
start new instances.

Pausing is a different axis from the status and stays a flag of its own: a paused workflow still
accepts new instances — they are born paused — while a disabled one accepts none. A workflow can
be both.

**The file and the runtime are two answers to the same question, and the stricter one wins.** The
engine keeps them apart: what the `.ec` declares in `status`, and what an operator has done. A
re-import replaces the declaration and leaves the runtime state alone, so it no longer puts back
into service anything that had been disabled; and **Enable** is refused — and not offered — for a
workflow whose own definition says `DISABLED` or `ARCHIVED`. Change that in the file and import it
again.

## Versioning

The engine keeps a **history of every version** of a definition. Whenever a definition is saved
(imported from Git, or its content otherwise changed) the engine hashes its *content* — the steps
and configuration, **excluding** the runtime state (pause, and the operator's disable/archive) and the
`version` field — and, if that hash differs from the latest recorded version, records a new
immutable version: an incrementing number (starting at **1**), a **creation timestamp**, and a
frozen JSON snapshot. Re-importing an unchanged definition, or a runtime toggle (pause/disable/
archive), records **nothing** — only a real content change bumps the version.

Because numbering is engine-owned, the `version` field authored in the `.ec` is **ignored** for
numbering (and overwritten on the head record). Every process instance already captures the version
it was created with, so the engine can attribute each running or finished process to the exact
version it ran.

In the management UI, a definition's detail view has a **Versions** tab listing every recorded
version with its creation date and how many processes ran with it — **running**, **completed** and
**total**. Processes created before versioning existed (whose captured version matches no recorded
version) are grouped under a single **legacy (pre-versioning)** row.

> Versioning is available with **JPA persistence** (`workflow.persistence=jpa`). In memory mode the
> Versions tab is not shown.

## Importing from Git

When `workflow.persistence=jpa`, EventConductor can clone one or more Git repositories at startup and import every `.json` / `.yaml` / `.yml` file that contains a valid workflow definition (i.e. has both `name` and `steps` fields).

### Configuration

```yaml
workflow:
  git-import:
    repositories:
      - url: https://github.com/your-org/workflow-defs.git
        branch: main          # optional, defaults to "main"
        username: my-user     # optional — for HTTPS with token auth
        password: ghp_xxx     # optional — personal access token
```

Multiple repositories are supported. Each repository is cloned into a temporary directory, scanned recursively, and deleted immediately after import.

### Startup import

Repositories are imported automatically on application startup by `WorkflowDefinitionGitImportRunner`. If a definition with the same ID already exists in the database it is overwritten (upsert). If a definition has no `id`, one is generated automatically. (Definitions are imported ready to run — there is no draft/active lifecycle.)

### Git webhook

To re-import definitions automatically after a push or merge, point your git provider's
webhook at EventConductor.

**Endpoint:** `POST /workflow/webhooks/{provider}` where `{provider}` is one of `github`,
`gitlab`, `bitbucket` or `generic` (unknown values are treated as `generic`). `/github` keeps
its original behaviour.

**`application.yml`:**

```yaml
workflow:
  git-import:
    webhook-secret: mysecret   # optional — see "Authentication" below
    repositories:
      - url: https://github.com/your-org/workflow-defs.git
        branch: master
        username: my-user
        password: ghp_xxx
```

**GitHub setup:** in your definitions repository go to *Settings → Webhooks → Add webhook*,
set the Payload URL to `https://your-server/workflow/webhooks/github`, Content type
`application/json`, the Secret to your `webhook-secret`, and send *Just the push event*.

**Behaviour:**

- Responds **202 Accepted** immediately and imports in the background (a provider's short
  delivery timeout is never hit); progress is logged at `INFO`.
- **Only the repository and branch that changed are reloaded.** The payload is parsed for the
  pushed repository URL and branch; EventConductor reimports only the configured repositories
  whose `url` and `branch` match. A push to a branch or repository nothing is configured for
  is acknowledged and ignored (`202`, "ignored"). If the payload can't be parsed, it falls
  back to reloading every configured repository (unchanged legacy behaviour).
- **Removed definitions are pruned.** A definition that was previously imported from a repo
  (and had an explicit `id`) but is no longer present is **archived** (the runtime `archived` flag);
  running processes are unaffected. Only git-imported definitions are ever pruned — classpath
  and hand-authored ones are never touched. (Pruning is tracked per running instance and
  resets on restart, repopulating on the next import.)

**Authentication** (per provider, using `webhook-secret`; blank disables it — internal
networks only):

| Provider | Endpoint | Verification |
|---|---|---|
| GitHub | `/workflow/webhooks/github` | HMAC-SHA256 of the body in `X-Hub-Signature-256` |
| GitLab | `/workflow/webhooks/gitlab` | secret token in `X-Gitlab-Token` |
| Bitbucket | `/workflow/webhooks/bitbucket` | HMAC-SHA256 in `X-Hub-Signature` (Bitbucket Server) |
| Generic | `/workflow/webhooks/generic` | shared token in `X-Webhook-Token` |

A missing or invalid signature/token is rejected with `401 Unauthorized`.

The **Forms** and **Rules** engines expose the same webhook under `/forms/webhooks/{provider}`
and `/rules/webhooks/{provider}` (configured via `forms.git-import.*` / `rules.git-import.*`).
Because forms and rules have no lifecycle status, pruning **deletes** them rather than
archiving.

## Step fields

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique identifier within the workflow |
| `type` | enum | — | `START` \| `ACTION` \| `USER_TASK` \| `PROCESS` \| `FORK` \| `JOIN` \| `END` \| `TIMER` \| `WAIT_FOR_MESSAGE` \| `SEND_MESSAGE` \| `RULE` \| `DYNAMIC` |
| `name` | string | — | Human-readable name |
| `description` | string | — | Optional description |
| `preconditionStepId` | string | — | Single step that must complete before this one starts |
| `preconditionStepIds` | string[] | — | Steps that must **all** complete before this one starts; takes precedence over the singular `preconditionStepId` when non-empty |
| `preconditions` | object[] | — | The incoming links, each `{ "stepId": …, "expression": …, "onFalse": … }` — a condition that says when arriving *by that route* counts. Takes precedence over `preconditionStepIds`/`preconditionStepId`. A link whose condition is false is not satisfied; `onFalse` says what that means — `WAIT` (default) holds the step, `DISCARD` makes it a branch not taken. See [Step Types](/reference/step-types/#what-a-false-condition-means-onfalse) |
| `preconditionExpression` | string | — | JEXL expression; the step does not run while it evaluates to `false`. The older, step-wide spelling: it is folded into every one of the step's links, and a step it holds back is **skipped** rather than held — see [Step Types](/reference/step-types/) |
| `parallel` | boolean | `false` | **Deprecated and ignored** — every eligible step runs concurrently; kept only so old definition files keep deserializing |
| `topic` | string | `downstream` | Destination this step's task (and its cancellation) is dispatched to, so a step may go to a worker pool of its own. Kafka mode only; embedded mode has one executor and ignores it |
| `formId` | string | — | Form identifier (USER_TASK only) |
| `childWorkflowDefinitionId` | string | — | Child workflow ID (PROCESS only, required; must differ from the workflow's own id) |
| `outputVariables` | string[] | — | PROCESS only: names of the child process variables copied back into the parent when the child completes; empty/absent = none |
| `ruleId` | string | — | Rule to evaluate (RULE only) |
| `duration` | duration | `0` | TIMER only: how long to wait from the moment the step starts. ISO 8601 string (`PT72H`, `P3D`) or integer milliseconds |
| `untilVariable` | string | — | TIMER only: process variable holding an ISO 8601 date/date-time the timer waits for. Takes precedence over `duration` |
| `messageName` | string | — | WAIT_FOR_MESSAGE / SEND_MESSAGE only (**required** for both): name of the message this step waits for (WAIT_FOR_MESSAGE) or emits (SEND_MESSAGE) |
| `correlationExpression` | string | — | WAIT_FOR_MESSAGE / SEND_MESSAGE only (**required** for both): JEXL expression yielding the correlation key — the key an incoming message must carry, or the key stamped on the outgoing message. Use `businessKey` to correlate by business key |
| `messageVariables` | string[] | — | SEND_MESSAGE only: names of the process variables the outgoing message carries. Empty or absent = none — process state is never sent implicitly |
| `timeout` | duration | `0` | Max execution time. ISO 8601 string (`PT30S`, `PT5M`, `PT1H30M`) or integer milliseconds. `0` = no timeout |
| `retries` | integer | `0` | Auto-retry attempts on ERROR or TIMEOUT |
| `compensable` | boolean | `false` | Trigger compensation step on failure |
| `compensationStepId` | string | — | Step to run as compensation (required when `compensable: true`) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on successful runs of this step per process instance; `0` inherits the workflow's `defaultMaxStepExecutions` |
| `joinType` | enum | `AND` | JOIN only: `AND` (default) is a synchronizing join that waits for **all** incoming branches; `XOR` is an exclusive join that proceeds on **any one**. Null/absent = `AND` |

See [Step Types](/reference/step-types/) for the semantics of each type.

## Execution model: pure dataflow

Steps run **by data flow, not by array order**. A step starts when it has not run yet
(`CREATED`), **all** of its preconditions have `COMPLETED`, and every condition on them holds —
a step-level `preconditionExpression` is folded into each of the step's links, so there is one
check rather than two — and every eligible step starts **concurrently**. There is no ordering
beyond the precondition graph: an active step never blocks unrelated branches. Parallelism is
expressed structurally — several steps sharing a precondition fan out ([`FORK`](/reference/step-types/#fork)
makes that explicit), and one step declaring several preconditions is a barrier. A
[`JOIN`](/reference/step-types/#join) makes that barrier explicit and, via its `joinType`, chooses
the merge semantics: **`AND`** (the default) waits for **all** incoming branches, while **`XOR`**
proceeds as soon as **any one** completes. The `parallel` flag is deprecated and ignored.

Because only preconditions drive eligibility, every flow needs an explicit entry point: a step
with no preconditions must be a `START` (completes instantly at process creation) or a
`WAIT_FOR_MESSAGE` (armed at process creation, waiting for its message).

:::note[Migrating pre-dataflow definitions]
Older definitions relied on array order and `parallel: true`, and often had a first step with
no preconditions. To migrate: **add one `START` step and point your old first steps at it**;
express any intended barriers with `preconditionStepIds`; drop `parallel` (it is ignored). A
compensation step also needs a precondition now — anchor it to the step it compensates and
guard it with `"preconditionExpression": "false"` so the normal dataflow never starts it (the
compensation pipeline starts it directly and does not evaluate the guard).
:::

## Validation at load

Beyond the JSON schema, the engine checks these invariants when a definition is loaded or saved (`WorkflowDefinition.checkInvariants()`) and rejects the definition if any is violated:

- **Duplicate step ids** — every `id` must be unique within the workflow.
- **Self-reference** — a step cannot be one of its own preconditions or its own `compensationStepId`.
- **Dangling references** — every id in `preconditionStepIds`/`preconditionStepId` and `compensationStepId` must point to an existing step.
- **Reachability (roots rule)** — a step with no preconditions never starts by itself, so it must be something that another mechanism starts: a `START`, a `WAIT_FOR_MESSAGE` that begins a flow, or a step named as some other step's `compensationStepId` (started by the rollback pipeline). Anything else with no preconditions is rejected — nothing would ever start it. Conversely, a `START` step must have **no** preconditions.
- **At most one START** — a workflow has a single entry point (or enters via `WAIT_FOR_MESSAGE`); more than one `START` is rejected. Multiple `END` steps are fine — a flow may finish through several distinct outcomes.
- **Precondition cycles** — the precondition graph must be acyclic (A waits for B waits for … waits for A would deadlock). Steps may declare several preconditions, so the check is a DFS over the multi-edge graph.
- **TIMER required fields** — a `TIMER` step must define a positive `duration` or a non-blank `untilVariable`.
- **Message required fields** — a `WAIT_FOR_MESSAGE` or `SEND_MESSAGE` step must define a non-blank `messageName` **and** a non-blank `correlationExpression`.
- **PROCESS required fields** — a `PROCESS` step must define a `childWorkflowDefinitionId`, and it must differ from the workflow's own id (direct self-recursion is rejected).

The [Maven plugin](/reference/maven-plugin/) mirrors the structural checks (duplicate/dangling/self references, the roots rule, START-without-preconditions, multi-edge cycle detection, the PROCESS child id) at build time; the TIMER/message value checks are only verified at engine load.

:::tip[Gateway-model guidance (warnings, not errors)]
The engine also logs non-fatal **warnings** nudging you toward the gateway model: a normal step
with more than one incoming flow should be a `JOIN` (so its AND/XOR semantics are explicit), and
one with more than one outgoing flow should be a `FORK` (a parallel split — every guarded branch
runs) or a `CHOICE` (an exclusive split — exactly one branch runs). These are compensation-aware
(the false-guarded anchor edge into a compensation step is excluded) and never block a definition.
:::

## Examples

### Linear workflow

**JSON:**

```json
{
  "id": "order-processing",
  "name": "Order Processing",
  "version": 1,
  "steps": [
    {
      "id": "start",
      "type": "START",
      "name": "Start"
    },
    {
      "id": "validate",
      "type": "ACTION",
      "name": "Validate Order",
      "topic": "order-validator",
      "preconditionStepId": "start"
    },
    {
      "id": "charge",
      "type": "ACTION",
      "name": "Charge Payment",
      "topic": "payment-service",
      "preconditionStepId": "validate",
      "timeout": "PT30S",
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

**YAML:**

```yaml
id: order-processing
name: Order Processing
version: 1
steps:
  - id: start
    type: START
    name: Start

  - id: validate
    type: ACTION
    name: Validate Order
    topic: order-validator
    preconditionStepId: start

  - id: charge
    type: ACTION
    name: Charge Payment
    topic: payment-service
    preconditionStepId: validate
    timeout: PT30S
    retries: 2

  - id: ship
    type: ACTION
    name: Ship Order
    topic: fulfillment-service
    preconditionStepId: charge

  - id: end
    type: END
    name: Done
    preconditionStepId: ship
```

### Workflow with human approval

```json
{
  "id": "expense-approval",
  "name": "Expense Approval",
  "version": 1,
  "steps": [
    {
      "id": "start",
      "type": "START",
      "name": "Start"
    },
    {
      "id": "submit",
      "type": "ACTION",
      "name": "Register Expense",
      "topic": "expense-service",
      "preconditionStepId": "start"
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
  "steps": [
    {
      "id": "start",
      "type": "START",
      "name": "Start"
    },
    {
      "id": "check",
      "type": "ACTION",
      "name": "Check Order",
      "topic": "order-checker",
      "preconditionStepId": "start"
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
  "steps": [
    {
      "id": "start",
      "type": "START",
      "name": "Start"
    },
    {
      "id": "reserve-hotel",
      "type": "ACTION",
      "name": "Reserve Hotel",
      "topic": "hotel-service",
      "preconditionStepId": "start",
      "compensable": true,
      "compensationStepId": "cancel-hotel",
      "retries": 2
    },
    {
      "id": "reserve-flight",
      "type": "ACTION",
      "name": "Reserve Flight",
      "topic": "flight-service",
      "preconditionStepId": "reserve-hotel",
      "compensable": true,
      "compensationStepId": "cancel-flight"
    },
    {
      "id": "cancel-hotel",
      "type": "ACTION",
      "name": "Cancel Hotel Reservation",
      "topic": "hotel-service",
      "preconditionStepId": "reserve-hotel",
      "preconditionExpression": "false"
    },
    {
      "id": "cancel-flight",
      "type": "ACTION",
      "name": "Cancel Flight Reservation",
      "topic": "flight-service",
      "preconditionStepId": "reserve-flight",
      "preconditionExpression": "false"
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

Compensation steps are anchored to the step they compensate and guarded with
`"preconditionExpression": "false"`: the normal dataflow never starts them (the guard is
falsy), but the compensation pipeline starts them directly when the compensable step exhausts
its retries — it does not evaluate the guard. The anchor satisfies the roots rule.
