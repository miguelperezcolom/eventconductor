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
| `id` | string | Unique workflow identifier. Optional — a file that declares none gets an id derived from its path (see [A file that declares no `id`](#a-file-that-declares-no-id)) |
| `name` | string | Human-readable name |
| `version` | integer | **Ignored for numbering.** The engine assigns versions itself on each content change (see [Versioning](#versioning)); any value here is not authoritative and is overwritten |
| `description` | string | Optional description |
| `tags` | array | Optional tags, short labels for finding and grouping definitions and their processes in the console (`[billing, team:payments]`; letters, digits, `.`, `_`, `:`, `/`, `-`). Metadata: changing them mints no version. The Definitions page lists and searches them; the Processes page filters by **Tag**, as does the [REST listing](/reference/processes-rest-api/#listing) (`?tag=`) |
| `retention` | string \| object | How long a finished process is kept before the purge deletes it, counted from its end: `P30D` for every terminal status, or one per status (`{completed: P30D, error: P180D}`); `forever` keeps them. Unset: the engine defaults decide, and they are off unless configured. Metadata: changing it mints no version. See [Process retention](#process-retention) |
| `searchAttributes` | object | Named values every process is indexed by, computed from its variables: `customerId: "${customerId}"` (a keyword) or `amount: { value: "${total}", type: number }` — types `keyword`, `text`, `number`, `date`, `bool`; at most 20. Part of the version. See [Searching and bulk operations](/guides/searching-and-bulk-operations/) |
| `paused` | boolean | Runtime flag — **not authored in the `.ec`**. Toggled at runtime (UI, `PauseWorkflowUseCase`/`ResumeWorkflowUseCase`, MCP). While `true`, all the definition's processes are held and new instances (cron included) are created born-`PAUSED`. Kept in the schema (default `false`) only so exported definitions round-trip |
| `status` | string | `ACTIVE` (default), `DISABLED` or `ARCHIVED`. **Authored in the `.ec`**, and a floor: an operator can take a workflow out of service at runtime, but cannot put one into service that its own definition closes. This is how a definition lives in the repository without being live |
| `disabled`, `archived` | boolean | The older way of saying the same thing, still read: `disabled: true` means `status: DISABLED`, `archived: true` means `status: ARCHIVED` |
| `limitConcurrentExecutions` | boolean | Cap concurrent running instances |
| `maxConcurrentExecutions` | integer | Max instances (if limit enabled) |
| `enqueueOnLimit` | boolean | Queue new instances when limit reached |
| `cronExpression` | string | Spring cron expression (six fields, seconds first). Unless the definition is disabled or archived, the engine creates a process instance at each occurrence. `null` = no scheduled starts |
| `triggers` | array | Event triggers, each `{id?, destination, type, filter?, businessKey?, variables?}`: the domain events (CloudEvents) that start a process of this definition — a logical destination, a type (`*` wildcards), a JEXL filter, and templates over the event for the business key and the initial variables. Each event starts at most one process per trigger. The same entries, naming their workflow, can live in an `events.yaml` next to the definitions. See [Starting on and waiting for domain events](/guides/domain-event-triggers/) |
| `schedules` | array | Scheduled starts, each `{id?, cron, zone?, variables?}`: an optional name (one schedule per id), a Spring cron, an optional IANA zone (default `workflow.time.zone`) and the initial process variables. Coexists with `cronExpression`; entries can also live in a `cron.yaml` next to the definitions (see [Starting a process](/guides/starting-a-process/#schedules-with-parameters)) |
| `defaultMaxStepExecutions` | integer | Default cap on how many times each step may successfully run within one process instance; a step's own `maxSuccessfulExecutions` overrides it. Enforced when a `repeat` or `loopBack` would run a step again (see [Loops and Multi-Instance](/guides/loops-and-multi-instance/#caps)). `0` or `null` = no cap of its own |
| `maxSteps` | integer | Cap on the total number of step executions one process instance may ever hold, runtime injections included (see [Dynamic Workflows](/guides/dynamic-workflows/)). `0` or `null` falls back to the engine-wide `workflow.dynamic.max-steps-per-process` default; any positive value overrides it for this definition's processes |
| `layout` | object | Where each step's node sits on the diagram, keyed by step id: `{x, y}` in whole pixels. Written by the graph editor as you drag, not by hand. Absent means the graph is laid out automatically (see [Diagram layout](#diagram-layout)) |

### Diagram layout

A workflow's graph is laid out automatically unless somebody has arranged it. Drag a node in the
graph editor — VS Code or IntelliJ — and the arrangement is written into the file under `layout`:

```yaml
steps:
  - id: start
    type: START
  - id: charge
    type: ACTION
    preconditionStepId: start

layout:
  start:  {x: 60, y: 160}
  charge: {x: 320, y: 160}
```

It says nothing about what the workflow does; it is there so the picture you arranged is the picture
everyone gets — in the console's diagrams as well as in your editor, since those are drawn from the
engine rather than from your working copy.

A few things worth knowing:

- **Arranging is all or nothing.** The first drag writes a coordinate for every step, not just the
  one you moved. Half an arrangement would let the auto-layout re-place the rest on whoever's machine
  is drawing it, on top of the steps you did place.
- **The arrangement survives rewiring.** Drawing or deleting a link changes which lines are drawn,
  not where you put the boxes. Only an unarranged graph re-flows.
- **A step with no coordinate is placed automatically** — one added since you arranged the workflow,
  or one a [DYNAMIC step](/guides/dynamic-workflows/) injected while a process was running.
- **"Re-layout" undoes it.** The button hands the graph back to the automatic layout and removes
  `layout` from the file.
- **Moving a node does not mint a new version.** A version is something a process can be rolled back
  to, and a coordinate changes nothing a process could run differently (see [Versioning](#versioning)).
- **A file nobody arranged does not change.** No `layout` key is written, and none is exported.

A coordinate naming a step that no longer exists is ignored rather than refused: it is a stale
coordinate, not a broken workflow.

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

### Process retention

Nothing deletes a finished process by default: the database keeps every process, with its steps,
variables and history, forever. A definition can say how long its finished processes are worth
keeping, and the engine purges them once that has passed:

```yaml
retention: P30D            # every terminal status, 30 days after the process ended
```

```yaml
retention:                 # or one per terminal status
  completed: P30D
  cancelled: P30D
  error: P180D             # failures are kept longer, for the post-mortem
  compensated: P30D
  compensationFailed: forever
```

Each value is an ISO-8601 duration in days, hours, minutes or seconds (`P30D`, `PT12H`, `P1DT12H`),
counted from the moment the process **ended**, or `forever`. Months and years are not durations —
their length depends on the calendar — so write `P365D`. Zero and negative values are refused, both
by the engine and by the [Maven plugin](/reference/maven-plugin/).

**Which retention applies** — the first that says something:

1. the definition's `retention` for that status (a status the per-status form does not list falls
   through), `forever` included — it keeps the process whatever the defaults say;
2. `workflow.processes.retention-by-status.<status>`;
3. `workflow.processes.retention`;
4. nothing: kept forever.

The engine defaults are off unless configured (see [Configuration](/reference/configuration/#process-retention)),
so upgrading purges nothing a definition did not ask to purge. A definition's detail page shows the
retention its processes get, and where it comes from.

**What is purged.** A purge job runs every `workflow.processes.purge.interval` (10 minutes), on one
pod at a time, and deletes the process with everything that belongs to it: its step executions
(variables and message subscriptions included), history, resources, trace context, the synchronous
invocation that started it, its row in the process index and its entries in the analytics ledger.
The analytics **daily rollups are kept**: the report still counts the process on the days it ran.
Each purged process is counted in `eventconductor.processes.purged` and each pass is logged.

**What is never purged:**

- a process that has not ended — `PENDING`, `RUNNING`, `PAUSED`;
- a process with a step still at work, waiting out a retry backoff (`AWAITING_RETRY`) or queued on a
  lock, one that holds a lock or waits for one, or one with an outbox message not yet sent;
- a child process on its own. A process tree — a process and every process its `PROCESS` steps
  started, recursively — is purged **together**, in one transaction, when its **root**'s retention
  has passed and every process in it has ended. The root's definition and status decide; a child
  whose own retention is longer goes with its root, and a child whose parent is still running stays
  however old it is.

Whatever keeps a tree is looked at again on the next pass.

**Consequences worth knowing:**

- **`ERROR` is purgeable**, by its own retention. Once purged, an `ERROR` process can no longer be
  retried or restarted — keep `error` long enough for someone to look at it.
- **The business key is free again.** A new process may be started with the key of a purged one
  (until then, the key is taken).
- **Retention is metadata, not versioned.** Changing it mints no version, and the purge judges every
  process by its definition's retention **as it is now**, whatever version the process ran — shorten
  it and the older processes go on the next pass. Processes of a definition that no longer exists are
  judged by the engine defaults.
- **Old processes count too.** A process cancelled before 2.28 was not stamped with its end; migration V45 backfills it from its last step's end (else its start or creation), so retention applies to it like to any other.
- Works with both persistence modes. In memory mode it bounds a long-running embedded engine's heap.

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

## Importing from a directory

Definitions that are already on disk — mounted into the container, checked out beside the app,
written by whatever generates them — are imported at startup from the directories you name:

```yaml
workflow:
  directory-import:
    directories:
      - /definitions/workflows
```

or, in the standalone app, `WORKFLOW_DEFINITIONS_DIRS=/definitions/workflows` (comma-separated).

Each directory is scanned recursively for `.json` / `.yaml` / `.yml` / `.ec` files, and every file
that has both `name` and `steps` is imported. A directory that is not there is reported as an
error rather than passed over in silence — the failure this saves you from is a typo in a mount
path, an engine that starts clean, and an empty definition list for no stated reason.

Definitions removed from the directory are **pruned** (archived) on the next import, exactly as
they are for a repository, and tracked separately per directory.

### A file that declares no `id`

`id` is optional, and a file that leaves it out is given one **derived from its path** relative to
the scan root: `sagas/onboarding.ec` becomes `sagas.onboarding`, with the separators as dots because
an id travels in URLs and in event payloads, where a slash is a separator.

The point of deriving it rather than generating one is that it is the same next time. Until 2.2.2 it
was a fresh UUID per import, which meant nothing connected the definition an import created to the
one the previous import had created from that same file: **every import inserted another copy**, and
none of them could be pruned. With a git webhook wired up, every push added one, without bound.

Two consequences, and they are choices rather than accidents:

- **Moving or renaming a file is a delete plus a create.** The old path stops being imported, so
  its definition is pruned (archived); the new path arrives as a new definition. A definition that
  must survive a move should declare an `id` — that is what declaring one is for.
- **The id is relative to the scan root**, so changing `directory` in the import configuration
  changes the id of every file that declares none, exactly as moving them would.

An explicit `id` always wins, and a derived one never takes it: the ids a scan declares are read
before anything is imported, so a file whose path would produce an id another file claims is
reported as an error and skipped rather than saved over it.

:::caution[Upgrading from 2.2.1 or earlier]
Definitions already inserted under generated UUIDs are not attributable to any file, so the upgrade
cannot clean them up: the next import creates one stable definition per file and leaves the old
copies where they are. Archive or delete them once, by hand. From then on the count stays put.
:::

:::tip[Directory or Git?]
Git import reads what is **committed**, which is what a deployment wants and what the loop where
someone is *writing* a definition does not: edit, commit, restart, discover the commit was the step
you forgot. Point the engine at a directory while authoring, at a repository when shipping. Both
can be configured at once.
:::

## Importing from Git

When `workflow.persistence=jpa`, EventConductor can clone one or more Git repositories at startup and import every `.json` / `.yaml` / `.yml` / `.ec` file that contains a valid workflow definition (i.e. has both `name` and `steps` fields).

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
  and hand-authored ones are never touched. With `workflow.persistence=jpa` what each source
  imported is kept in the database (`imported_definition_source`), so the first import after a
  restart prunes a definition removed while the engine was down; in memory mode it is tracked per
  running instance and repopulates on the next import.

**Authentication** (per provider, using `webhook-secret`). Since 2.28.0 an **unsigned** webhook
(no `webhook-secret` set) is refused with `401` while repositories are configured, unless the
application is protected by Spring Security (the standalone apps are: the provider then sends
HTTP Basic credentials in the payload URL) or `workflow.message-api.allow-anonymous=true` — the
same rule as the engine's REST API. A webhook makes the engine clone and replace definitions, so
set the secret:

| Provider | Endpoint | Verification |
|---|---|---|
| GitHub | `/workflow/webhooks/github` | HMAC-SHA256 of the body in `X-Hub-Signature-256` |
| GitLab | `/workflow/webhooks/gitlab` | secret token in `X-Gitlab-Token` |
| Bitbucket | `/workflow/webhooks/bitbucket` | HMAC-SHA256 in `X-Hub-Signature` (Bitbucket Server) |
| Generic | `/workflow/webhooks/generic` | shared token in `X-Webhook-Token` |

A missing or invalid signature/token is rejected with `401 Unauthorized`. A body over
`workflow.rest.max-body-bytes` (16 MiB) is refused unread with `413`.

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
| `maxSuccessfulExecutions` | integer | `0` | Cap on successful runs of this step per process instance, enforced when a loop would run it again; `0` inherits the workflow's `defaultMaxStepExecutions` |
| `forEach` | object | — | Run the step once per item of a list — `{items, as?, indexAs?, mode?, maxConcurrency?, output?, outputElement?, completion?}` (ACTION, USER_TASK, PROCESS, HTTP_CALL, LLM_CALL, RULE). See [Loops and Multi-Instance](/guides/loops-and-multi-instance/) |
| `repeat` | object | — | Run the step again until a condition holds — `{until, maxIterations, delay?}` |
| `loopBack` | object | — | An explicit back-edge: run the steps from `to` to here again while `while` holds — `{to, while?, maxIterations, delay?}`. Ordinary precondition cycles stay an error |
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
- **Loops and multi-instance** — a loop is declared, never drawn as a cycle: `forEach`, `repeat` and `loopBack` are checked by the shared `LoopRules` (the step types that may carry them, `loopBack.to` upstream of its step, no REPLY or END inside a loop's section, loops nested or apart, required `maxIterations`) and their JEXL must parse. See [Loops and Multi-Instance](/guides/loops-and-multi-instance/#rules).
- **TIMER required fields** — a `TIMER` step must define a positive `duration` or a non-blank `untilVariable`.
- **Message required fields** — a `WAIT_FOR_MESSAGE` or `SEND_MESSAGE` step must define a non-blank `messageName` **and** a non-blank `correlationExpression`.
- **PROCESS required fields** — a `PROCESS` step must define a `childWorkflowDefinitionId`, and it must differ from the workflow's own id (direct self-recursion is rejected).

The [Maven plugin](/reference/maven-plugin/) mirrors the structural checks (duplicate/dangling/self references, the roots rule, START-without-preconditions, multi-edge cycle detection, the PROCESS child id, the loop and multi-instance rules) at build time; the TIMER/message value checks are only verified at engine load.

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

### What an expression may contain

Every expression in a definition — `preconditionExpression`, a link's `condition`, a
`correlationExpression` — is **untrusted input**: definitions arrive from a git import or from the
editor, and they evaluate on the thread that owns the process. The engine runs them in a sandbox,
so a few things a JEXL expression can normally do are refused here.

| Refused | Why |
| --- | --- |
| Reflection, `System`, `Runtime`, `new` | The road to RCE. JEXL runs under `RESTRICTED` permissions |
| Loops, lambdas, scripts | The only way an expression could spin forever on an orchestration thread |
| Assignment to a process variable | A guard reads the process; it must not rewrite what it is reading |
| More than 4096 characters, or brackets nested more than 64 deep | Overflows the parser's stack. Both ceilings are configurable — see [Configuration](/reference/configuration/) |

An expression that breaks one of these does not evaluate, and a step whose guard did not evaluate
**does not run**. Failing closed is deliberate: a guard nobody could evaluate is not a guard that
passes.

#### Regular expressions

The `=~` and `!~` operators match on [RE2](https://github.com/google/re2j), not on
`java.util.regex`. RE2 matches in time linear in the length of the input, which is what stops a
pattern like `(a+)+$` — 8 characters — from pinning a core for the rest of the afternoon on an
input a worker wrote.

Two consequences worth knowing before you write a guard:

- **`=~` is a whole-string match, not a substring search.** `'hello world' =~ 'world'` is
  `false`; write `'.*world.*'` if that is what you meant. This is JEXL's own semantics and has
  not changed — it is just worth stating, because it surprises people.
- **Lookahead, lookbehind and backreferences are not supported.** `(?=…)`, `(?!…)`, `(?<=…)` and
  `\1` are what make backtracking necessary, so RE2 does without them. A pattern using one is
  refused with a message saying so, and the step does not run. A lookahead can nearly always be
  rewritten as an alternation.

Everything else — character classes, quantifiers, anchors, groups, alternation — works as you
would expect.

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
