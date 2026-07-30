---
title: Workflow Definitions
description: The EventConductor workflow DSL — steps, branching, retries, and more. Supports JSON and YAML.
---

Workflow definitions describe the steps of a business process. They can be written in **JSON** or **YAML** (`.json`, `.yaml`, `.yml`), are version-controlled, and are reviewable in a pull request.

In `embedded` + `memory` mode, definitions are loaded from `classpath:/workflows/` at startup. In `jpa` persistence mode, they can also be imported from Git — either at startup, on demand via the MCP tool `importWorkflowDefinitionsFromGit`, or automatically via a **GitHub webhook**.

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
  "status": "ACTIVE",
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
status: ACTIVE
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
| `version` | integer | Version number |
| `description` | string | Optional description |
| `status` | enum | `DRAFT` \| `ACTIVE` \| `DISABLED` \| `ARCHIVED` |
| `draftOfId` | string | ID of the production definition this is a working copy of. `null` for production definitions. Set automatically by the UI. |
| `limitConcurrentExecutions` | boolean | Cap concurrent running instances |
| `maxConcurrentExecutions` | integer | Max instances (if limit enabled) |
| `enqueueOnLimit` | boolean | Queue new instances when limit reached |
| `cronExpression` | string | Spring cron expression (six fields, seconds first). While the definition is `ACTIVE`, the engine creates a process instance at each occurrence. `null` = no scheduled starts |
| `defaultMaxStepExecutions` | integer | Default cap on how many times each step may successfully run within one process instance; a step's own `maxSuccessfulExecutions` overrides it. `0` or `null` = unbounded |

### Definition statuses

| Status | Description |
|---|---|
| `DRAFT` | Under construction, not executable |
| `ACTIVE` | Ready to accept new process instances |
| `DISABLED` | No new instances allowed; running ones continue |
| `ARCHIVED` | Retired definition |

## Lifecycle

A definition moves through those four statuses over its life. The management UI offers exactly the transitions that are valid for the current status (see the [UI Manual](/guides/ui-manual/)) — every arrow below is one toolbar action.

<svg viewBox="0 0 880 600" width="880" height="600" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Workflow definition lifecycle. A new definition starts as DRAFT. From ACTIVE you can create a working copy — a DRAFT linked to the live definition — and promote it back to ACTIVE, or disable it to DISABLED and enable it back. DRAFT and DISABLED definitions can be archived to ARCHIVED; an ACTIVE definition must be disabled before archiving. An ARCHIVED definition can be reactivated back to DRAFT. Edit is available in every status except ACTIVE." style="max-width:100%;height:auto;font-family:inherit">
  <defs>
    <marker id="lc-arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M 0 1 L 9 5 L 0 9 z" fill="currentColor" opacity="0.65"/>
    </marker>
  </defs>
  <g stroke="currentColor" fill="none" stroke-opacity="0.55" marker-end="url(#lc-arr)">
    <path d="M 12 316 L 52 316"/>
    <path d="M 412 286 L 412 172"/>
    <path d="M 468 172 L 468 283"/>
    <path d="M 517 308 L 672 308"/>
    <path d="M 672 328 L 517 328"/>
    <path d="M 748 348 Q 645 470 518 494"/>
    <path d="M 168 348 Q 268 432 362 486"/>
    <path d="M 372 514 Q 214 502 116 350"/>
  </g>
  <g stroke="none" fill="currentColor" font-size="11" opacity="0.85">
    <text x="32" y="308" text-anchor="middle">New</text>
    <text x="402" y="228" text-anchor="end">Create working copy</text>
    <text x="480" y="228" text-anchor="start">Promote to production</text>
    <text x="594" y="301" text-anchor="middle">Disable</text>
    <text x="594" y="343" text-anchor="middle">Enable</text>
    <text x="676" y="436" text-anchor="middle">Archive</text>
    <text x="250" y="408" text-anchor="middle">Archive</text>
    <text x="236" y="508" text-anchor="middle">Reactivate</text>
  </g>
  <g font-size="14" font-weight="600" text-anchor="middle">
    <rect x="55" y="286" width="150" height="60" rx="10" stroke="currentColor" stroke-opacity="0.6" fill="currentColor" fill-opacity="0.03"/>
    <text x="130" y="312" fill="currentColor">DRAFT</text>
    <text x="130" y="330" fill="currentColor" font-size="10.5" font-weight="400" opacity="0.6">new · editable</text>
    <rect x="365" y="286" width="150" height="60" rx="10" stroke="#C27D2C" stroke-width="2" fill="#C27D2C" fill-opacity="0.12"/>
    <text x="440" y="312" fill="currentColor">ACTIVE</text>
    <text x="440" y="330" fill="currentColor" font-size="10.5" font-weight="400" opacity="0.65">live</text>
    <rect x="675" y="286" width="155" height="60" rx="10" stroke="currentColor" stroke-opacity="0.6" fill="currentColor" fill-opacity="0.03"/>
    <text x="752" y="312" fill="currentColor">DISABLED</text>
    <text x="752" y="330" fill="currentColor" font-size="10.5" font-weight="400" opacity="0.6">paused</text>
    <rect x="335" y="110" width="210" height="60" rx="10" stroke="#C27D2C" stroke-opacity="0.6" stroke-dasharray="5 4" fill="#C27D2C" fill-opacity="0.05"/>
    <text x="440" y="136" fill="currentColor">Working copy</text>
    <text x="440" y="154" fill="currentColor" font-size="10.5" font-weight="400" opacity="0.65">DRAFT linked to a live def</text>
    <rect x="365" y="478" width="150" height="60" rx="10" stroke="currentColor" stroke-opacity="0.6" fill="currentColor" fill-opacity="0.03"/>
    <text x="440" y="504" fill="currentColor">ARCHIVED</text>
    <text x="440" y="522" fill="currentColor" font-size="10.5" font-weight="400" opacity="0.6">retired</text>
  </g>
</svg>

- **New → `DRAFT`** — a definition you create in the UI starts as a `DRAFT`. Definitions loaded from the classpath (`classpath:/workflows/`) or imported from Git come in as `ACTIVE`.
- **Create working copy** (`ACTIVE` → *working copy*) — clones a live definition into a `DRAFT` linked back to it via `draftOfId`. Only one working copy may exist per definition.
- **Promote to production** (`DRAFT` → `ACTIVE`) — offered on any `DRAFT`. A working copy (one with `draftOfId` set) is promoted by copying its content onto the original definition, bumping the original's version, and deleting the copy; a standalone draft is simply activated in place.
- **Disable / Enable** (`ACTIVE` ⇄ `DISABLED`) — stop or resume accepting new process instances; already-running instances are unaffected.
- **Archive** (`DRAFT` or `DISABLED` → `ARCHIVED`) — retire a definition. An `ACTIVE` definition must be **disabled first**; archive is not offered while it is live.
- **Reactivate** (`ARCHIVED` → `DRAFT`) — bring a retired definition back as a `DRAFT`, so it re-enters the lifecycle from the start.

The **Edit** action is available in every status **except `ACTIVE`**: a live definition is read-only and is changed through a working copy, never in place.

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

Repositories are imported automatically on application startup by `WorkflowDefinitionGitImportRunner`. If a definition with the same ID already exists in the database it is overwritten (upsert). If a definition has no `id`, one is generated automatically.

### GitHub webhook

To re-import definitions automatically after a push or merge to a branch, configure EventConductor as a GitHub webhook receiver.

**`application.yml`:**

```yaml
workflow:
  git-import:
    webhook-secret: mysecret   # optional — same value you set in GitHub repo settings
    repositories:
      - url: https://github.com/your-org/workflow-defs.git
        branch: main
        username: my-user
        password: ghp_xxx
```

**GitHub setup:** in your definitions repository go to *Settings → Webhooks → Add webhook* and fill in:

| Field | Value |
|---|---|
| Payload URL | `https://your-server/workflow/webhooks/github` |
| Content type | `application/json` |
| Secret | same value as `workflow.git-import.webhook-secret` |
| Events | *Just the push event* (or *Pull requests* filtered to `closed` + `merged`) |

**Behaviour:**

- The endpoint responds **202 Accepted** immediately so GitHub's 10-second delivery timeout is never hit.
- The import runs in the background; progress is logged at `INFO` level.
- If `webhook-secret` is set, the `X-Hub-Signature-256` header is verified using HMAC-SHA256. Requests with a missing or invalid signature are rejected with `401 Unauthorized`.
- If `webhook-secret` is blank, any caller can trigger an import (suitable for internal networks only).

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
| `type` | enum | — | `START` \| `ACTION` \| `USER_TASK` \| `PROCESS` \| `FORK` \| `JOIN` \| `END` \| `TIMER` \| `WAIT_FOR_MESSAGE` \| `SEND_MESSAGE` \| `RULE` |
| `name` | string | — | Human-readable name |
| `description` | string | — | Optional description |
| `preconditionStepId` | string | — | Single step that must complete before this one starts |
| `preconditionStepIds` | string[] | — | Steps that must **all** complete before this one starts; takes precedence over the singular `preconditionStepId` when non-empty |
| `preconditionExpression` | string | — | JEXL expression; the step does not run while it evaluates to `false` |
| `parallel` | boolean | `false` | **Deprecated and ignored** — every eligible step runs concurrently; kept only so old definition files keep deserializing |
| `topic` | string | — | Worker topic/destination (ACTION only) |
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
| `rollbackable` | boolean | `false` | Trigger compensation step on failure |
| `compensationStepId` | string | — | Step to run as compensation (required when `rollbackable: true`) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on successful runs of this step per process instance; `0` inherits the workflow's `defaultMaxStepExecutions` |

See [Step Types](/reference/step-types/) for the semantics of each type.

## Execution model: pure dataflow

Steps run **by data flow, not by array order**. A step starts when it has not run yet
(`CREATED`), **all** of its preconditions have `COMPLETED`, and its `preconditionExpression`
(if any) is truthy — and every eligible step starts **concurrently**. There is no ordering
beyond the precondition graph: an active step never blocks unrelated branches. Parallelism is
expressed structurally — several steps sharing a precondition fan out ([`FORK`](/reference/step-types/#fork)
makes that explicit), and one step declaring several preconditions is a barrier
([`JOIN`](/reference/step-types/#join)). The `parallel` flag is deprecated and ignored.

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
- **Entry points (roots rule)** — every step with no preconditions must be a `START` or a `WAIT_FOR_MESSAGE`: every flow must enter through one. Conversely, a `START` step must have **no** preconditions.
- **Precondition cycles** — the precondition graph must be acyclic (A waits for B waits for … waits for A would deadlock). Steps may declare several preconditions, so the check is a DFS over the multi-edge graph.
- **TIMER required fields** — a `TIMER` step must define a positive `duration` or a non-blank `untilVariable`.
- **Message required fields** — a `WAIT_FOR_MESSAGE` or `SEND_MESSAGE` step must define a non-blank `messageName` **and** a non-blank `correlationExpression`.
- **PROCESS required fields** — a `PROCESS` step must define a `childWorkflowDefinitionId`, and it must differ from the workflow's own id (direct self-recursion is rejected).

The [Maven plugin](/reference/maven-plugin/) mirrors the structural checks (duplicate/dangling/self references, the roots rule, START-without-preconditions, multi-edge cycle detection, the PROCESS child id) at build time; the TIMER/message value checks are only verified at engine load.

## Examples

### Linear workflow

**JSON:**

```json
{
  "id": "order-processing",
  "name": "Order Processing",
  "version": 1,
  "status": "ACTIVE",
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
status: ACTIVE
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
  "status": "ACTIVE",
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
  "status": "ACTIVE",
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
  "status": "ACTIVE",
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
falsy), but the compensation pipeline starts them directly when the rollbackable step exhausts
its retries — it does not evaluate the guard. The anchor satisfies the roots rule.
