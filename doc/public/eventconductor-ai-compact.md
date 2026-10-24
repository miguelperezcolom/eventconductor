# EventConductor — AI Reference (Compact)

EventConductor is an event-driven **workflow / saga orchestration engine** for Java/Spring. A business
process is a **workflow definition** (YAML or JSON) made of **steps** and the preconditions between them;
the engine schedules steps, enforces retries/timeouts/deadlines and runs compensation, and delegates business
logic to **workers** (or to built-in steps: HTTP_CALL, LLM_CALL, PUBLISH_EVENT…). The same
definitions run in one JVM with no dependencies or on a Kafka + PostgreSQL cluster.

Artifacts: `io.mateu.workflow:*`, one version for all (2.33.0 at the time of writing; if Central does not
serve it yet, use the newest `<release>` of `https://repo1.maven.org/maven2/io/mateu/workflow/workflow-engine/maven-metadata.xml`).
Spring Boot 4.1, Java 21. Full reference: `eventconductor-ai-full.md`. Examples of every pattern:
`.claude/skills/eventconductor/reference/patterns.md` in the repo.

---

## Set up a project (no archetype, no BOM)

```xml
<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>4.1.1</version></parent>
<properties><java.version>21</java.version><eventconductor.version>2.33.0</eventconductor.version></properties>
<dependencies>
  <dependency><groupId>io.mateu.workflow</groupId><artifactId>workflow-engine</artifactId><version>${eventconductor.version}</version></dependency>
  <dependency><groupId>io.mateu.workflow</groupId><artifactId>workflow-test</artifactId><version>${eventconductor.version}</version><scope>test</scope></dependency>
</dependencies>
<build><plugins>
  <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
  <plugin><groupId>io.mateu.workflow</groupId><artifactId>workflow-maven-plugin</artifactId><version>${eventconductor.version}</version>
    <executions><execution><goals><goal>validate</goal></goals></execution></executions></plugin>
</plugins></build>
```
Add only what you use: `spring-boot-starter-webmvc` (serve REST/UI/MCP — optional in the engine),
`forms-engine` (USER_TASK), `rule-engine`/`rule-runtime` (RULE), `worker-embedded` (typed `TaskHandler`s
in-process; also serves PUBLISH_EVENT), `worker-http` + `worker-embedded` (HTTP_CALL in-process), `worker-llm` +
`worker-embedded` + `spring-ai-anthropic`/`spring-ai-openai` (LLM_CALL) — or `builtin-workers` (all built-in
steps in one dependency; LLM_CALL still needs a provider), `inbound-events-kafka` (kafka mode with
triggers/`onEvent`), `flyway-core` (jpa).

```java
@WorkflowEmbeddedApplication           // io.mateu.workflow.autoconfigure; NOT @SpringBootApplication in embedded mode
public class OrdersApplication {
    public static void main(String[] args) { SpringApplication.run(OrdersApplication.class, args); }
}
```
```properties
workflow.mode=embedded            # embedded | kafka
workflow.persistence=memory       # memory | jpa
workflow.message-api.allow-anonymous=true   # LOCAL DEV ONLY — see Security
```
Definitions: `src/main/resources/workflows/<id>.workflow.yaml` (also `.yaml/.yml/.json/.ec`; `events.yaml`
and `cron.yaml` there hold triggers/schedules). Loop: `mvn eventconductor:validate` → `mvn test` → `mvn spring-boot:run`.

## Deployment modes

| mode + persistence | use | notes |
|---|---|---|
| `embedded` + `memory` (default) | tests, local, embedding | state lost on restart; migration only to the current version |
| `embedded` + `jpa` | single node, durable | add `@EnableJpaRepositories` + `@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")`, `flyway-core`, `ddl-auto=validate` |
| `kafka` + `jpa` | multi-pod | events keyed by process (one owner per partition); set `spring.cloud.function.definition=consumeOutbox;consumeUpstream`; bindings are contributed by the engine |
| sharded | very large | each shard its own DB and topics (`workflow.sharding.enabled`) |

Flyway migrations target PostgreSQL and H2. Standalone images: `orchestrator-standalone-app`,
`forms-standalone-app`, `worker-standalone-app`, `http-worker-standalone-app`, `llm-worker-standalone-app`,
`rule-standalone-app`, `webhook-gateway-standalone-app` (`apps/docker-compose.yml`: orchestrator on 8105,
HTTP Basic `admin:$SECURITY_PASSWORD`). The orchestrator serves HTTP_CALL, LLM_CALL and PUBLISH_EVENT itself
(built-in workers, group `orchestrator-builtin-workers`, `BUILTIN_WORKER_TOPICS`); the http/llm worker images
are optional, to keep egress and its secrets out of the orchestrator.

---

## Definition rules

- **Every step has `id`, `type`, `name`.** Unknown keys are schema errors.
- **At most one `START`**; every other step needs a precondition — except compensation steps and a
  `WAIT_FOR_MESSAGE` beginning a flow.
- **Order is data flow**: a step runs when ALL its preconditions are `COMPLETED` and its guards hold; all
  eligible steps run concurrently. Array order is irrelevant; `parallel` is ignored.
- **No precondition cycles** — use `repeat` / `loopBack`.
- **One `END`**; converge branches with `JOIN` (`joinType: AND` after FORK, `XOR` after CHOICE).
- Preconditions: `preconditionStepId` (one), `preconditionStepIds` (all), or `preconditions: [{stepId,
  expression?, onFalse?: WAIT|DISCARD}]` (a guard per incoming link; `WAIT` holds the step, `DISCARD` = branch
  not taken). `preconditionExpression` = a step-wide `DISCARD` guard.
- **Variables are strings** (JSON text for structures); worker outputs merge into the process. JEXL in guards
  and mappings (`amount > 1000`, `status == 'OK'`); `${expr}` templates in payloads (a leaf that is exactly
  one `${expr}` keeps its type; context: variables, `process`, `step`, `now`, `businessKey`).
- **Saga:** `compensable: true` + `compensationStepId` on the step; the compensation step has NO
  preconditions. On final failure completed compensable steps are undone in reverse → `COMPENSATED`
  (`COMPENSATION_FAILED` if an undo fails; `ERROR` if nothing to undo). `rollbackable` = old spelling.
- **No secrets** in definitions (HTTP via profiles / `${secret:NAME}`, LLM keys in config).

```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json
id: booking-saga
name: Booking with a cooling-off period
version: 1
steps:
  - { id: start, type: START, name: Start }
  - { id: reserve, type: ACTION, name: Reserve the room, topic: reserve-room, preconditionStepId: start,
      compensable: true, compensationStepId: release }
  - { id: charge, type: ACTION, name: Charge the card, topic: charge-card, preconditionStepId: reserve,
      retries: 2, timeout: PT30S, compensable: true, compensationStepId: refund }
  - { id: cooling-off, type: TIMER, name: Three days, duration: P3D, preconditionStepId: charge }
  - { id: capture, type: ACTION, name: Capture the payment, topic: capture-payment, preconditionStepId: cooling-off }
  - { id: release, type: ACTION, name: Release the room, topic: release-room }
  - { id: refund, type: ACTION, name: Refund the card, topic: refund }
  - { id: end, type: END, name: End, preconditionStepId: capture }
```

## Which step type / pattern?

| You want | Use |
|---|---|
| work in your code | `ACTION` (+ worker; `topic` routes it in kafka mode, `task` names a contract) |
| REST call without a worker | `HTTP_CALL` `http: {connection, method, path, body, output, retryOn}` |
| model call / structured extraction | `LLM_CALL` `llm: {connection, system, prompt, responseSchema, output}` |
| governed agent from the IA catalogue | `AGENT` `agent: {ref, taskPrompt, input, outputVariable}` |
| human approval / input | `USER_TASK` `formId` (+ `assignment`, `escalation`) |
| exactly one branch by data | `CHOICE` + guarded `preconditions` links (+ unguarded default) → `JOIN joinType: XOR` |
| parallel branches | `FORK` … `JOIN` (`preconditionStepIds`) |
| wait a duration / until a date | `TIMER` `duration` \| `untilVariable` \| `until: {date, offset, at, zone, ifPast}` |
| finish by a moment / route on timeout | `deadline`, `timeout` + `onTimeoutStepId` |
| wait for a callback | `WAIT_FOR_MESSAGE` `messageName` + `correlationExpression` (+ `onEvent` for a domain event) |
| send a request and wait for its reply | `awaitReply` on `HTTP_CALL`/`PUBLISH_EVENT` (never request → separate wait: races) |
| emit a domain event | `PUBLISH_EVENT` `event: {destination, type, key, payload}` |
| start on an event / webhook / schedule | `triggers` (or `events.yaml`) / `webhook-gateway` / `schedules`, `cronExpression` (or `cron.yaml`) |
| caller waits over HTTP | `syncInvocation.enabled` + `REPLY` |
| change a running process, get an answer | definition `updates` (+ update-with-start) |
| once per list item / poll / rework | `forEach` / `repeat` / `loopBack` (`maxIterations` required) |
| sub-process | `PROCESS` `childWorkflowDefinitionId`, `outputVariables` |
| one-at-a-time per entity | `LOCK`/`UNLOCK` or `processLock` |
| decision table | `RULE` `ruleId` |
| debounce/throttle/singleton starts | `startPolicy` (definition or trigger) |
| endless process | `CONTINUE_AS_NEW` + `businessKeyPolicy` |
| find / clean up / protect | `searchAttributes`, `retention`, `sensitiveVariables` |

## Step types

| Type | Notes |
|---|---|
| `START` | single entry point; no preconditions |
| `ACTION` | task to a worker; `timeout`, `retries`, `priority`, `task`, `topic` |
| `USER_TASK` | `formId`; `assignment: {assignee, candidateUsers, candidateGroups, priority, dueDate, followUpDate}`; `escalation: [{after, action: reassign\|notify\|escalateTo, to, escalateTo, publishEvent}]`; submitted values → variables. Forms are loaded by `forms.directory-import.directories`/git, not the classpath |
| `RULE` | `ruleId`; outputs → variables |
| `TIMER` | exactly one of `duration`, `untilVariable`, `until` |
| `WAIT_FOR_MESSAGE` | `messageName` + `correlationExpression` (`businessKey` for the key); `onEvent: {destination, type, correlationKey, filter, variables}`; unmatched messages are dropped, not buffered |
| `SEND_MESSAGE` | emit a message (`messageVariables`); fire-and-forget |
| `PUBLISH_EVENT` | domain event, rendered by the engine, sent by built-in `publish-event@1` (worker-events; topic `event-publications`), which completes the step after sending; at-least-once, stable `ce_id`; destination mapped by `workflow.events.destinations.<name>.topic`; CloudEvents on Kafka |
| `HTTP_CALL` | built-in `http-call@1` (worker-http; topic `http-calls`, served by the orchestrator in kafka mode); `output` = JEXL over `{status, headers, body}`; default `retryOn: [5xx, io]` (4xx final); same `Idempotency-Key` on retries |
| `LLM_CALL` | built-in `llm-call@1` (worker-llm; `llm-calls`); `responseSchema` validated (`LLM_INVALID_OUTPUT`); `output` over `{text, json, usage, model}` |
| `AGENT` | invoke an agent published in the IA catalogue (`ref` checked at import; prompt, model, tools, output schema are the catalogue's): task `run-agent@1` on `agent-calls`, variable `__agent`, `invocationId` on every reply; `retries: 0`, timeout 5 min; result = exactly `outputVariable` (JSON, `json:parse(x).f`); uncertain outcome → `REVIEW_REQUIRED` + process `PAUSED` until reconciled; `forEach` = one invocation per item |
| `PROCESS` | child with ALL parent variables, key `parent:<stepExecutionId>`; `outputVariables` come back |
| `FORK` / `JOIN` / `CHOICE` | gateways (see above); CHOICE picks the longest satisfied guard first, latches |
| `LOCK` / `UNLOCK` | `lockName`, `lockKey` (JEXL); waiters park `WAITING_ON_LOCK`, FIFO |
| `REPLY` | answer a sync caller: `replyVariables` \| `replyExpression` \| `replyTemplate`; at most one per run |
| `CONTINUE_AS_NEW` | `continueAsNew: {version: same\|latest, variables}`; fresh run, same business key; nothing after it |
| `DYNAMIC` | a worker injects steps at runtime (advanced) |
| `END` | completes the process |

Top-level fields: `id`, `name`, `version`, `description`, `status` (`DRAFT|ACTIVE|DISABLED|ARCHIVED`), `steps`,
`tags`, `triggers`, `schedules`, `cronExpression`, `syncInvocation {enabled, onFailure, onLockBusy,
defaultDeadlineMs}`, `updates`, `startPolicy {key, debounce, throttle {limit, period, onLimit: queue|drop},
singleton: skip|replace}`, `businessKeyPolicy` (`unique|allowAfterFinished|allowAfterFailed|terminateRunning`),
`processLock {name, key}`, `retention` (`P30D` or per status, `forever`), `searchAttributes` (≤ 20, typed),
`sensitiveVariables`, `requiredRoles`/`requiredScopes`, `defaultMaxStepExecutions`,
`limitConcurrentExecutions`/`maxConcurrentExecutions`/`enqueueOnLimit`.

### Short patterns

Request and wait for the reply (armed in the same transaction as the request):
```yaml
- id: ask-quote
  type: PUBLISH_EVENT
  name: Ask for a quote
  preconditionStepId: start
  timeout: PT5M
  onTimeoutStepId: no-quote
  event: { destination: quote-requests, type: com.acme.quote.requested, key: "${process.businessKey}",
           payload: { requestId: "${process.businessKey}", item: "${item}" } }
  awaitReply:
    messageName: quote-ready
    correlationExpression: businessKey
    onEvent: { destination: quote-replies, type: com.acme.quote.ready, correlationKey: "${data.requestId}",
               variables: { price: "${data.price}" } }
```

Started by an event; HTTP call; fan-out:
```yaml
id: onboarding
name: Booking onboarding
version: 1
triggers:
  - { id: on-created, destination: bookings, type: com.acme.booking.created,
      businessKey: "onboarding-${data.bookingId}", variables: { bookingId: "${data.bookingId}", rooms: "${data.rooms}" } }
steps:
  - { id: start, type: START, name: Start }
  - id: register
    type: HTTP_CALL
    name: Register the booking
    preconditionStepId: start
    retries: 2
    http: { connection: crm, method: POST, path: "/bookings/${bookingId}", output: { crmId: "body.id" } }
  - id: prepare-room
    type: ACTION
    name: Prepare a room
    topic: housekeeping
    preconditionStepId: register
    forEach: { items: rooms, as: room, mode: parallel, output: prepared }
  - { id: end, type: END, name: End, preconditionStepId: prepare-room }
```

---

## Workers

- **Embedded, low level:** ONE `EmbeddedTaskExecutor` bean gets every task (topic ignored); branch on `stepId()`;
  report with `UpdateStepExecutionUseCase` using `taskExecutionId` (not the step id) and
  `io.mateu.workflow.domain.aggregates.Variable` (events use `io.mateu.workflow.dtos.Variable`). It runs on the
  dispatching thread — use timeouts; `workflow.embedded.worker-threads` for a pool. It also receives built-in
  HTTP/LLM tasks, so with those use typed handlers.
```java
@Bean
EmbeddedTaskExecutor taskExecutor(UpdateStepExecutionUseCase update) {
    return req -> update.handle(new UpdateStepExecutionCommand(
            req.taskExecutionId(), List.of(new Variable("chargeId", "ch-1")), "",
            StepExecutionStatus.COMPLETED));                 // RUNNING | COMPLETED | ERROR
}
```
- **Typed:** a `.ectask` contract + `generate-worker-sources` → implement `<Id>V1Task`; or a hand-made
  `TaskRegistration<In, Out>` bean (`new TaskRegistration<>("charge", 1, "payments", In.class, Out.class, (in, ctx) -> out)`),
  matched by task id or, for an ACTION with no `task`, its step id. Runs with `worker-embedded` or `worker-kafka`.
  Throw `TaskFailure(code, reason, retryable)` for business failures.
- **Kafka raw:** consume `TaskExecutionRequested` (`taskExecutionId, processId, workflowDefinitionId, stepId,
  taskId, variables, attempt, priority`) from `downstream`/the topic; reply with `WorkerReply.completed(bridge, req,
  vars)` / `WorkerReply.failed(bridge, req, vars, reason)` — never ignore `bridge.send`'s `false`. Idempotent handlers.
- **HTTP, any language:** `workflow.workers.http.enabled=true`, `.topics=payments`, `.api-keys.<name>=<key>`;
  `POST /workflow/api/workers/activate` (long poll), `/tasks/{id}/complete|fail|heartbeat`; Python SDK
  `sdks/python/eventconductor-worker` (`Worker(url, api_key=…)`, `@worker.task("payments")`, `worker.run()`).

## Validate and test

- `mvn eventconductor:validate` (plugin goal `validate`, bound to `process-resources`): schema + engine rules for
  `src/main/resources/{workflows,forms,rules,tasks}`. `mvn eventconductor:check-migration -Dfrom=<git ref>`
  before shipping a new version of a running workflow.
- `workflow-test` (`io.mateu.workflow.test`): `@EventConductorTest` boots the engine in memory with a test clock;
  inject `WorkflowTestRuntime`:
```java
@EventConductorTest
class BookingSagaTest {
    @Test
    void compensatesWhenTheCaptureFails(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete();
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_2"));
        runtime.whenTask("capture-payment").thenFail("CARD_EXPIRED", false);   // final
        runtime.whenTask("refund").thenComplete();
        runtime.whenTask("release-room").thenComplete();
        var booking = runtime.start("booking-saga", "B-2", Map.of("amount", 120));
        runtime.advanceTime(Duration.ofDays(3));                                // fires the TIMER
        assertThat(booking).isCompensated().hasPassedSteps("refund", "release").hasNotPassedSteps("capture");
    }
}
```
  `whenTask(task|step id|topic)` / `whenStep(id)` → `thenComplete(map)`, `thenFail(code[, retryable])`,
  `thenTimeout()`, `thenAnswer(fn)`; `whenHttpCall(step).thenRespond(status, body)`, `whenLlmCall(step).thenAnswer(text)`;
  `whenAgent(step).thenOutput(json)` / `.thenReviewRequired()` (+ `resolveAgentReview(p, step)`) / `.thenFail(code, msg)`;
  `completeUserTask(p, step, values)`,
  `sendMessage`, `publishEvent`, `update`, `updateWithStart`, `advanceTime`. Assertions (`WorkflowAssertions.assertThat`):
  `isCompleted/isCompensated/isFailed/isWaitingAt/hasPassedSteps/hasVariable/hasReplied/hasPublishedEvent`.
  Unique business key per test; don't run kit tests in parallel in one JVM.

## Call and operate (REST `/workflow/api`, MCP)

- Processes: `POST /processes {definitionId, businessKey?, variables?}` (201; 200 existing key; 202/429 by
  `startPolicy`), `GET /processes?definitionId&status&tag&query&attr.<name>[.gte]…`, `/by-key?businessKey=`,
  `/{id}`, `/steps`, `/variables`, `/logs`, `POST /{id}/cancel|pause|resume|retry|restart`. OpenAPI:
  `/openapi/eventconductor-api.yaml`; live `/v3/api-docs/eventconductor` with springdoc + `workflow.api.openapi.enabled=true`.
- `POST /messages {messageName, correlationKey, variables}`; `POST /events/{destination}` (CloudEvent);
  `POST /definitions/{id}/invocations` (`Idempotency-Key`, `Prefer: wait=N`); `POST /processes/{id}/updates/{name}`;
  `POST /definitions/{id}/update-with-start`.
- Repair (audited, `reason` required): `POST /processes/{id}/variables {set, remove, reason}`,
  `/steps/{stepExecutionId}/complete`, `/retry-from/{stepId}`; incidents `GET /incidents`, `POST /incidents/{key}/retry`.
- Batch: `POST /batch-operations {type: CANCEL|RETRY|RESTART|PAUSE|RESUME|SET_VARIABLES|RETRY_FROM_STEP|MIGRATE, filter, …}`.
- Migration: `POST /processes/{id}/migrate/dry-run` then `/migrate {targetVersion, stepMapping, reason}`.
- Search query language: `customerId = "C-9" AND amount > 100` (ops `= > >= < <=`, AND only).
- MCP tools: `listProcesses`, `getProcessDetails`, `sendMessage`, `retryProcess`, `restartProcess`,
  `setProcessVariables`, `completeStepManually`, `retryProcessFromStep`, `listIncidents`, `retryIncident`,
  `createBatchOperation`, `migrateProcess`, `updateProcess`, `updateWithStart`, `publishInboundEvent`,
  schedule/trigger/forms/rules tools… No start-process tool.

## Security (secure by default since 2.27/2.28)

- `/workflow/api/**`: `X-Api-Key` = `workflow.message-api.api-key`, or a Spring Security chain, or
  `workflow.message-api.allow-anonymous=true`; else 401. Consoles (admin UI, MCP, rule reads): Spring Security or
  `allow-anonymous`; else 403. `workflow.ui.enabled=false` unmounts the UI; `@EnableWorkflowUi` for embedded apps
  mounting it. Standalone apps: HTTP Basic, set `SECURITY_PASSWORD`.
- HTTP credentials: `workflow.http.connections.<name>`, profiles `workflow.http.auth.<name>` (bound to hosts via
  `.hosts` or `workflow.http.allowed-hosts`, else `HTTP_FORBIDDEN_AUTH`), `${secret:NAME}` only from
  `workflow.http.secrets.<NAME>`. LLM keys: `workflow.llm.connections.<name>.api-key`.
- `sensitiveVariables` need `workflow.encryption.keys.<id>` (base64, 32 bytes) + `active-key`; masked in UI/REST/MCP;
  reveal with `POST /processes/{id}/variables/{name}/reveal {reason}` for `workflow.security.sensitive.reveal-roles`;
  never usable as search attribute, correlation, lock or startPolicy key; LLM_CALL needs `llm.allowSensitiveVariables: true`, AGENT (taskPrompt/input) `agent.allowSensitiveVariables: true`.
- AGENT catalogue: `workflow.agents.catalogue-url`, `token-uri`, `client-id`, `client-secret` (`WORKFLOW_AGENTS_*`).

## Gotchas and upgrades

- A step without `name`, two STARTs, a step with no way in, a cycle → refused. AND-JOIN after a CHOICE never fires.
- Business keys are unique across workflows: put the workflow in trigger keys. Reuse needs `businessKeyPolicy`.
- forEach outputs are not on the process (read `output`); `maxIterations` required on `repeat`/`loopBack`.
- Kafka consumers (main after 2.33.0) redeliver retryable failures with unlimited back-off
  (`workflow.consumer.retry.*`); records are never discarded, only parked on `dead-letter` when defective.
- 2.27: secrets from `workflow.http.secrets`, API refuses anonymous. 2.28: consoles refuse anonymous,
  `@EnableWorkflowUi`, HTTP credentials host-bound, signed git webhooks, `SECURITY_PASSWORD`. 2.31: upgrade forms
  engines and workers before using `escalation`. 2.32: sensitive variables need keys. 2.33: don't import
  `CONTINUE_AS_NEW`/reusing `businessKeyPolicy` definitions until every pod runs 2.33; `TaskExecutionRequested`
  gained `priority` (record patterns).
