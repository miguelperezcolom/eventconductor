# Workflow definition reference

A definition is YAML or JSON (`.yaml`/`.yml`/`.json`, or `.ec` holding either). Name files
`order.workflow.yaml` or `order.ec.yaml` so the IDE plugins open them as a graph with schema
validation; without an `id`, import names it `order`. Loaded from `classpath:/workflows/` (every mode),
`workflow.directory-import.directories`, or git import. Schema:
`modules/workflow-engine/src/main/resources/workflow-definition-schema.json` — add
`# yaml-language-server: $schema=https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json`
for autocomplete. The schema forbids unknown keys. Examples: [patterns.md](patterns.md).

## Top-level fields

| Field | Notes |
|---|---|
| `id`, `name` (required), `version`, `description` | `version` is an int; JPA mode versions content automatically |
| `status` | `DRAFT` \| `ACTIVE` \| `DISABLED` \| `ARCHIVED` |
| `steps` (required) | the step list |
| `tags` | `[billing, "team:payments"]` — metadata, no new version; Processes page filters by tag |
| `cronExpression` / `schedules` | Spring cron; `schedules: [{id?, cron, zone?, variables?}]` (or `cron.yaml` with `workflow:` per entry) |
| `triggers` | `[{id?, destination, type, filter?, businessKey?, variables?, startPolicy?}]` domain events that start an instance (or `events.yaml` with `workflow:` per entry) |
| `syncInvocation` | `{enabled, onFailure: REPLY_IMMEDIATELY\|REPLY_AFTER_COMPENSATION, onLockBusy: WAIT\|FAIL, defaultDeadlineMs}` — needs a reachable `REPLY` |
| `updates` | `[{name, description?, accepts (JSON Schema), acceptedWhile: [stepIds], validate: [{expression, message}], set: {var: JEXL}, completes: <WAIT_FOR_MESSAGE id>, reply \| replyTemplate, sensitive: [fields], allowSensitiveVariables?}]` |
| `startPolicy` | `{key?, debounce?, throttle?: {limit, period, onLimit: queue\|drop}, singleton?: skip\|replace}` for REST starts and sync invocations (triggers may have their own); no definition-level `debounce` with `syncInvocation` |
| `businessKeyPolicy` | `unique` (default) \| `allowAfterFinished` \| `allowAfterFailed` \| `terminateRunning` |
| `processLock` | `{name?, key}` — serialize whole instances by a JEXL key |
| `retention` | `P30D` \| `{completed, cancelled, error, compensated, compensationFailed: <duration>\|forever}` |
| `searchAttributes` | `{name: "${expr}"}` or `{name: {value: "${expr}", type: keyword\|text\|number\|date\|bool}}`, ≤ 20 |
| `sensitiveVariables` | names or globs; needs `workflow.encryption.keys.*` ([security.md](security.md)) |
| `requiredRoles`, `requiredScopes` | enforced with `workflow.security.flow-authorization.enabled=true` |
| `defaultMaxStepExecutions` | default cap on a step's successful runs (loops) |
| `limitConcurrentExecutions`, `maxConcurrentExecutions`, `enqueueOnLimit` | concurrency cap per definition |
| `paused` | runtime flag (pause/resume a whole definition); not an authoring decision |

## Step fields

| Field | Applies to | Notes |
|---|---|---|
| `id`, `type`, `name` | all | **all three required**; `description` optional |
| `preconditionStepId` / `preconditionStepIds` | all | one / ALL must complete first |
| `preconditions` | all | `[{stepId, expression?, onFalse?: WAIT\|DISCARD}]` — condition per incoming link; wins over the two above. `WAIT` (default) holds the step until variables change; `DISCARD` = branch not taken |
| `preconditionExpression` | all | step-wide JEXL guard = a `DISCARD` guard on every link (older spelling) |
| `timeout` | waiting/worker steps | ISO-8601 (`PT30S`) or ms; `TIMEOUT` → retry or failure |
| `retries` | worker steps | retries on ERROR/TIMEOUT with backoff (`workflow.retry.backoff-*`) |
| `onTimeoutStepId` | waiting steps | on timeout route to that step instead of failing (guard the target with `preconditions: [{stepId: <this>, expression: "false"}]`) |
| `deadline` | waiting steps | moment by which to finish (`{date, offset, at, zone}` or a date template); retries don't extend it |
| `compensable` + `compensationStepId` | worker steps | saga undo; compensation step has NO preconditions (`rollbackable` = old spelling) |
| `priority` | ACTION, HTTP_CALL, LLM_CALL, RULE | 0–100, default 50 |
| `topic` | worker steps | Kafka destination (default `downstream`; `http-calls`/`llm-calls`/`event-publications` for built-ins); embedded ignores it |
| `task` | ACTION | task contract `<id>` or `<id>@<version>` (`.ectask`) |
| `formId`, `assignment`, `escalation` | USER_TASK | see below |
| `ruleId` | RULE | rule from the rule catalog; outputs → variables |
| `duration` / `untilVariable` / `until` | TIMER | exactly one |
| `messageName` + `correlationExpression` | WAIT_FOR_MESSAGE, SEND_MESSAGE | both required; `correlationExpression: businessKey` correlates by business key |
| `onEvent` | WAIT_FOR_MESSAGE | `{destination, type, correlationKey?, filter?, variables?}` — a domain event is the message |
| `messageVariables` | SEND_MESSAGE | variables the message carries (absent = none) |
| `awaitReply` | HTTP_CALL, PUBLISH_EVENT | `{messageName, correlationExpression, onEvent?}` — wait for the reply in the same step |
| `childWorkflowDefinitionId`, `outputVariables`, `sensitiveOutputs` | PROCESS | child definition; variables copied back (absent = none) |
| `joinType` | JOIN | `AND` (default) \| `XOR` |
| `lockName`, `lockKey` | LOCK/UNLOCK | JEXL key |
| `replyVariables` \| `replyExpression` \| `replyTemplate` | REPLY | at most one |
| `event` | PUBLISH_EVENT | `{destination, type, key?, payload \| payloadTemplate \| payloadVariables, format?}` |
| `http` | HTTP_CALL | `{connection + path \| url, method, query, headers, body \| bodyTemplate, successStatus, output, auth, retryOn}` |
| `agent` | AGENT | `{ref, taskPrompt?, input?, outputVariable, mcp?, rag?, peers?, allowSensitiveVariables?}` — `ref` must be published in the IA catalogue (checked at import); `retries: 0`; default `timeout` 5 min |
| `llm` | LLM_CALL | `{connection, model?, system?, prompt, temperature?, maxTokens?, responseSchema?, output? \| outputVariable?, usageVariable?, allowSensitiveVariables?}` |
| `continueAsNew` | CONTINUE_AS_NEW | `{version?: same\|latest, variables?: [names] \| {name: template}}` |
| `forEach` | ACTION, USER_TASK, PROCESS, HTTP_CALL, LLM_CALL, RULE, AGENT | `{items, as?, indexAs?, mode?: parallel\|sequential, maxConcurrency?, output?, outputElement?, completion?: all\|any\|JEXL}` |
| `repeat` | same as forEach | `{until, maxIterations, delay?}` |
| `loopBack` | all but START/END/REPLY | `{to, while?, maxIterations, delay?}` |
| `maxSuccessfulExecutions` | all | cap enforced when a loop would run the step again |
| `parallel` | — | deprecated, ignored |

## Step types

- **START** — the single entry point (at most one per definition); no worker, completes at creation, no preconditions.
- **ACTION** — dispatch a task to a worker ([workers.md](workers.md)).
- **USER_TASK** — a human form (`formId`; forms-engine). `assignment: {assignee?, candidateUsers?,
  candidateGroups?, priority?, dueDate?, followUpDate?}` (templates rendered at creation; a task naming
  people is visible/claimable only by them, their groups and task admins). `escalation: [{after,
  action?: reassign|notify|escalateTo, to?, escalateTo?, publishEvent?}]` fired once each in order;
  only one `escalateTo`, last; not with `deadline`. Submitted values → variables.
- **RULE** — evaluate `ruleId` (rule-runtime); outputs → variables.
- **TIMER** — durable wait: `duration`, `untilVariable` (ISO date/date-time variable) or `until: {date:
  "${checkIn}", offset: -P3D, at: "09:00", zone: "${zone}", ifPast: fire|timeout}` (follows variable changes).
- **WAIT_FOR_MESSAGE** — wait for a message (REST `/workflow/api/messages`, Kafka `upstream`
  `message-received`, MCP `sendMessage`, a `SEND_MESSAGE`, or `onEvent`). Unmatched messages are dropped,
  not buffered. May begin a flow (no preconditions).
- **SEND_MESSAGE** — emit a message to another process; fire-and-forget; fails loud (ERROR) on bad config.
- **PUBLISH_EVENT** — domain event, rendered and checked by the engine when the step starts, sent by the
  built-in task `publish-event@1` (worker-events; topic `event-publications`, served by the orchestrator in
  kafka mode, by `worker-embedded` embedded), which completes the step after sending — at-least-once, `ce_id`
  stable, consumers dedupe. CloudEvents on Kafka; `ExternalEventPublisher` bean + loopback embedded;
  `workflow.events.destinations.<name>.topic` maps the name. Retries/timeouts/incidents like any task.
- **HTTP_CALL / LLM_CALL** — built-in tasks `http-call@1` (worker-http) and `llm-call@1`
  (worker-llm); kafka: served by the orchestrator itself (standalone http/llm worker apps optional).
  See [patterns.md](patterns.md) and [security.md](security.md).
- **AGENT** — invoke an agent published in the IA catalogue (its prompt, model, MCP/RAG/peers and output schema
  are the catalogue's): task `run-agent@1` on `agent-calls`, one variable `__agent`, an `invocationId` every reply
  names. Result: exactly `outputVariable`, as JSON (`json:parse(x).field` in guards). An uncertain outcome (runtime
  review, timeout, cancel while running) → step `REVIEW_REQUIRED`, process `PAUSED`, until the runtime reconciles it;
  operations that move steps are refused meanwhile. Guide: `doc/src/content/docs/guides/workflow-agents.md`.
- **PROCESS** — child workflow with ALL parent variables, business key `parent:<stepExecutionId>`;
  child ERROR/CANCELLED → step ERROR; parent cancel cascades.
- **FORK / JOIN / CHOICE** — no-worker gateways: FORK = all successors; CHOICE = exactly one (first
  satisfied guard, longest first; unguarded link = default; it latches); JOIN `AND` waits for all,
  `XOR` for the first. Pair FORK↔AND, CHOICE↔XOR.
- **LOCK / UNLOCK** — per-key critical section; a busy lock parks the step `WAITING_ON_LOCK` (FIFO).
- **REPLY** — answer a synchronous caller; at most one per run (two only on exclusive CHOICE branches);
  the flow carries on.
- **DYNAMIC** — a worker that injects steps at runtime (advanced; `workers` guide).
- **CONTINUE_AS_NEW** — end this run (`COMPLETED`, outcome `continued`) and start a fresh run with the
  same business key; nothing may follow it.
- **END** — completes the process; one per definition.

## Execution model in five lines

1. A step becomes eligible when all its preconditions are `COMPLETED` and its guards hold.
2. All eligible steps run concurrently; array order is irrelevant.
3. A falsy `DISCARD` guard = branch not taken (step ends `CANCELLED` when the process completes);
   a falsy `WAIT` link holds the step and keeps the process open.
4. A failure with retries left → `AWAITING_RETRY` → re-run; retries exhausted → compensation of
   completed compensable steps in reverse order → process `COMPENSATED` (or `COMPENSATION_FAILED`),
   or `ERROR` when there is nothing to compensate.
5. The process completes at `END`, or when nothing is runnable any more.

## Variables, JEXL and templates

- Variables are `(name, string)` pairs; structured values are JSON text (forEach/HTTP templates read them as structure).
- JEXL (guards, `correlationExpression`, `until` in `repeat`, `output` mappings): `amount > 1000`,
  `status == 'OK'`, `a && b`. Coerces numeric strings.
- Payload templates (`http` url/path/query/headers/body, `event.payload`, `replyTemplate`, trigger
  `businessKey`/`variables`): `${expr}` inside strings; a leaf that is exactly one `${expr}` keeps its type;
  `$${` escapes. Context: variables, `process {id, businessKey, workflowDefinitionId, version}`,
  `step {id, name, executionId}`, `now`, `businessKey`; triggers see the event (`data.x`, `id`, `subject`, `key`).

## Working copies (jpa)

A `DRAFT` clone (`draftOfId`) is edited while production runs; promoting copies it onto the original and
bumps the version (running processes keep theirs — migrate them explicitly if needed).
