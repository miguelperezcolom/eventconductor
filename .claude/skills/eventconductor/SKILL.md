---
name: eventconductor
description: Design, test and operate business processes with EventConductor — the Java/Spring event-driven workflow & saga orchestration engine (io.mateu.workflow). Use when writing a workflow definition (YAML/JSON steps), choosing a step type or pattern (saga/compensation, human approval, HTTP integration, event-triggered flows, request/response, forEach fan-out, agents with tools, process updates), implementing workers (embedded, Kafka, HTTP/Python), testing definitions with the workflow-test kit, validating them with the Maven plugin, calling the engine over REST/MCP, or operating running processes (incidents, retries, batch operations, migration). Triggers on workflow-engine, WorkflowDefinition, EmbeddedTaskExecutor, UpdateStepExecutionUseCase, ProcessUpstreamEventUseCase, @EventConductorTest, WorkflowTestRuntime, eventconductor:validate, /workflow/api, START/ACTION/USER_TASK/RULE/TIMER/WAIT_FOR_MESSAGE/HTTP_CALL/LLM_CALL/AGENT_CALL/PUBLISH_EVENT/REPLY/CHOICE/FORK/JOIN/PROCESS/CONTINUE_AS_NEW steps, workflow.mode, workflow.persistence.
---

# EventConductor

A **workflow definition** (YAML or JSON) is the process as data: steps and the preconditions between
them. The engine schedules steps, enforces retries/timeouts/deadlines and runs compensation.
**Business logic lives in stateless workers** (or in built-in steps: HTTP_CALL, LLM_CALL,
PUBLISH_EVENT…). Never hand-roll a state machine, scheduler or retry loop — declare it on the step.

Current release: **2.33.0** (`io.mateu.workflow:*`, Spring Boot 4.1, Java 21) — if Maven Central does not
serve it yet, use the newest `<release>` in
`https://repo1.maven.org/maven2/io/mateu/workflow/workflow-engine/maven-metadata.xml` (2.33-only
features: `updates`, `startPolicy`, `businessKeyPolicy`, `CONTINUE_AS_NEW`, `priority`, rate limits).

## Workflow for a newcomer

1. **Scaffold** the project → `eventconductor-scaffold` skill (pom, main class, properties).
2. **Write the definition** in `src/main/resources/workflows/<id>.workflow.yaml` → [patterns.md](reference/patterns.md) (copy-paste examples), [workflow-definitions.md](reference/workflow-definitions.md) (every field).
3. **Validate** → `mvn eventconductor:validate` (also runs in `mvn verify`).
4. **Test** with `workflow-test` (`@EventConductorTest`, mocks, `advanceTime`) → [testing.md](reference/testing.md).
5. **Implement workers** → [workers.md](reference/workers.md).
6. **Run** embedded or with docker-compose → `eventconductor-run` skill.
7. **Call & operate** over REST/MCP/Java (start, messages, updates, incidents, batch, migration) → [operations.md](reference/operations.md), [api.md](reference/api.md).
8. **Secure** it (API key, Spring Security, HTTP credentials, sensitive variables) → [security.md](reference/security.md).
9. **Before shipping / upgrading** → [gotchas.md](reference/gotchas.md) (rules, validation errors, breaking changes, modes).

## Which step type / pattern?

| You want | Use |
|---|---|
| automated work in your code | `ACTION` (+ a worker; `topic` routes it, `task` names a contract) |
| call a REST API without a worker | `HTTP_CALL` (`http: {connection, path, …}`; embedded: needs `worker-http` or `builtin-workers`; kafka: the orchestrator serves it) |
| classify / extract / summarize with a model | `LLM_CALL` (`llm: {connection, prompt, …}`; embedded: needs `worker-llm` or `builtin-workers` + a Spring AI provider; kafka: the orchestrator serves it) |
| run a governed agent (tools, RAG, peers) published in the IA catalogue | `AGENT` (`agent: {ref, taskPrompt?, input?, outputVariable}`; the IA runtime serves `agent-calls`) |
| human approval / data entry | `USER_TASK` (`formId`, `assignment`, `escalation`; forms-engine) |
| pick ONE branch by data | `CHOICE` + guarded `preconditions` links, converge with `JOIN joinType: XOR` |
| run branches in parallel, then wait for all | `FORK` … `JOIN` (`preconditionStepIds` = branch ends) |
| run only when a condition holds | `preconditions: [{stepId, expression, onFalse}]` or `preconditionExpression` |
| undo completed work on failure (saga) | `compensable: true` + `compensationStepId` (compensation step has no preconditions) |
| wait a duration / until a date | `TIMER` (`duration`, `untilVariable`, or `until: {date, offset, at, zone}`) |
| finish by a date or route elsewhere | `deadline` / `timeout` + `onTimeoutStepId` |
| wait for a callback / external signal | `WAIT_FOR_MESSAGE` (`messageName` + `correlationExpression`; `onEvent` for a domain event) |
| send a request AND wait for its reply | `awaitReply` on `HTTP_CALL` / `PUBLISH_EVENT` (never request → separate WAIT_FOR_MESSAGE) |
| emit a domain event | `PUBLISH_EVENT` (`event: {destination, type, payload}`) |
| start on a domain event / webhook | definition `triggers` (or `events.yaml`) |
| start on a schedule | `schedules` / `cronExpression` (or `cron.yaml`) |
| caller waits for the answer over HTTP | `syncInvocation.enabled` + a `REPLY` step |
| change a running process with validation and an answer | definition `updates` (+ update-with-start) |
| same step once per list item | `forEach` (parallel/sequential, `output`) |
| poll until a condition | `repeat: {until, maxIterations, delay}` |
| redo a section (rework loop) | `loopBack: {to, while, maxIterations}` — never a precondition cycle |
| reusable sub-process | `PROCESS` (`childWorkflowDefinitionId`, `outputVariables`) |
| one process at a time per entity | `LOCK`/`UNLOCK` or definition `processLock` |
| business rule / decision table | `RULE` (`ruleId`) |
| debounce/throttle/singleton starts per key | definition or trigger `startPolicy` |
| endless / very long process | `CONTINUE_AS_NEW` + `businessKeyPolicy` |
| find processes by business data | `searchAttributes` + `query=` |
| delete finished processes | `retention` |
| protect PII | `sensitiveVariables` + `workflow.encryption.keys.*` |

## Minimal definition

```yaml
id: order-processing
name: Order processing
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: validate
    type: ACTION
    name: Validate the order
    topic: order-validator
    preconditionStepId: start
  - id: charge
    type: ACTION
    name: Charge the card
    topic: payments
    preconditionStepId: validate
    timeout: PT30S
    retries: 2
  - id: end
    type: END
    name: Done
    preconditionStepId: charge
```

## Non-negotiable rules

- **Every step has `id`, `type` and `name`** (the schema requires `name`).
- **Exactly one `START`; every other step needs a precondition** — except compensation steps
  and a `WAIT_FOR_MESSAGE` beginning a flow.
- **Order is data flow, not array order.** A step runs when ALL its preconditions completed (and its
  guards hold); all eligible steps run concurrently. `parallel` is ignored.
- **No precondition cycles** — use `repeat` / `loopBack`.
- **One `END`**; funnel branches through a `JOIN` (`AND` after FORK, `XOR` after CHOICE).
- **Variables are strings** (JSON text for structures); worker outputs merge into the process.
- **Compensation steps declare no preconditions**; the step they undo sets `compensable: true`
  (`rollbackable` is the old spelling).
- **Report worker results with `taskExecutionId`**, never the step id.
- **No secrets in definitions**: HTTP credentials via named profiles / `${secret:NAME}`, LLM keys in config.
- **Secure by default**: the REST API needs `X-Api-Key`/Spring Security (401), the consoles need
  Spring Security (403); `workflow.message-api.allow-anonymous=true` only for local dev.

Canonical sources (prefer them if anything here looks stale): the docs site
(`doc/src/content/docs/` in https://github.com/miguelperezcolom/eventconductor), the schema
https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json,
and https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/doc/public/eventconductor-ai-full.md. The examples in this skill are validated by
`AiReferenceExamplesTest` (workflow-maven-plugin).
