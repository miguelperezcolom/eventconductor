---
title: Testing Workflows
description: Test your own workflow definitions in plain JUnit 5 — in memory, with task mocks, HTTP and LLM stubs, AssertJ assertions and a clock you move, so "wait three days" takes milliseconds.
---

A workflow definition is code: it has branches, deadlines, retries and a rollback path, and every
one of them deserves a test. The `workflow-test` module runs **your** definitions inside a JUnit 5
test — the real engine, in memory, with no server, broker or database — and gives the test three
things a running deployment cannot:

- **A clock you move.** `advanceTime(Duration.ofDays(3))` jumps from one due moment to the next —
  every TIMER, step timeout, retry backoff and cron occurrence on the way fires at its own moment —
  and returns in milliseconds. It is the same idea as Temporal's time-skipping test environment.
- **Mocks instead of workers.** Answer each task with a completion, a failure or silence; stub
  `HTTP_CALL` and `LLM_CALL` steps so a test never reaches the network or a model.
- **Assertions on the process.** `assertThat(process).isCompensated().hasPassedSteps("refund")`,
  in the spirit of Camunda's process test assertions.

## Add the dependency

```xml
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-test</artifactId>
  <version>${eventconductor.version}</version>
  <scope>test</scope>
</dependency>
```

It brings the engine, JUnit 5, Spring's test support and AssertJ with it. The kit lives in the
package `io.mateu.workflow.test`.

## A complete example: a saga with a three-day timer

The definition — a booking that reserves a room and charges a card, then waits out a three-day
cooling-off period before capturing the payment. Both the reservation and the charge are
compensable:

```json
{
  "id": "booking-saga",
  "name": "Booking with a three-day cooling-off period",
  "version": 1,
  "steps": [
    { "id": "start", "type": "START", "name": "Start" },
    { "id": "reserve", "type": "ACTION", "name": "Reserve the room", "topic": "reserve-room",
      "compensable": true, "compensationStepId": "release", "preconditionStepId": "start" },
    { "id": "charge", "type": "ACTION", "name": "Charge the card", "topic": "charge-card", "retries": 2,
      "compensable": true, "compensationStepId": "refund", "preconditionStepId": "reserve" },
    { "id": "cooling-off", "type": "TIMER", "name": "Three days to change your mind", "duration": "P3D",
      "preconditionStepId": "charge" },
    { "id": "capture", "type": "ACTION", "name": "Capture the payment", "topic": "capture-payment",
      "retries": 0, "preconditionStepId": "cooling-off" },
    { "id": "release", "type": "ACTION", "name": "Release the room", "topic": "release-room" },
    { "id": "refund", "type": "ACTION", "name": "Refund the card", "topic": "refund" },
    { "id": "end", "type": "END", "name": "End", "preconditionStepId": "capture" }
  ]
}
```

Put it where the engine always looks, `src/test/resources/workflows/booking-saga.json` (or in
`src/main/resources/workflows/` if it ships with your application). The test:

```java
import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

@EventConductorTest
class BookingSagaTest {

    @Test
    void capturesThePaymentOnceTheCoolingOffPeriodIsOver(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete(Map.of("roomId", "R-12"));
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_1"));
        runtime.whenTask("capture-payment").thenComplete();

        var booking = runtime.start("booking-saga", "B-1", Map.of("amount", 120));
        assertThat(booking).isWaitingAt("cooling-off").hasVariable("chargeId", "ch_1");

        runtime.advanceTime(Duration.ofDays(2));
        assertThat(booking).isWaitingAt("cooling-off");          // not yet

        runtime.advanceTime(Duration.ofDays(1));
        assertThat(booking).isCompleted()
                .hasPassedSteps("reserve", "charge", "cooling-off", "capture");
    }

    @Test
    void refundsAndReleasesWhenTheCaptureIsDeclined(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete();
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_2"));
        runtime.whenTask("capture-payment").thenFail("CARD_EXPIRED", false);   // final: no retry
        runtime.whenTask("refund").thenComplete();
        runtime.whenTask("release-room").thenComplete();

        var booking = runtime.start("booking-saga", "B-2", Map.of("amount", 120));
        runtime.advanceTime(Duration.ofDays(3));

        assertThat(booking).isCompensated()
                .hasPassedSteps("reserve", "charge", "refund", "release")
                .hasNotPassedSteps("capture");
        assertThat(runtime.invocationsOf("refund").getFirst().variable("chargeId")).isEqualTo("ch_2");
    }
}
```

Both tests together run in well under a second once the context is up: three days of business time
are three jumps of the clock.

## How a test runs

`@EventConductorTest` is a `@SpringBootTest` that boots the engine in **embedded mode with memory
persistence**, with:

- **the test clock as the engine clock.** Every moment the engine records or compares — process
  start and finish, a TIMER's due moment, a timeout deadline, a retry's backoff, a cron occurrence —
  is read from it, and it only moves when the test moves it;
- **no scheduler running on its own** (`workflow.schedulers.background=false`). The kit runs every
  scheduler pass itself, when the test asks for time to pass;
- **every task answered on the calling thread.** A call returns once the engine has done all it can
  at the current time, so there is nothing to `await`.

Definitions load from `classpath:/workflows/` as in any embedded application. Add others from files
or directories with `@EventConductorTest(definitions = {"src/test/flows", "classpath:extra/order.json"})`,
or at run time with `runtime.deploy(...)`.

The Spring context is cached between test classes with the same configuration, like any Spring test
context. Each test starts with no mocks and an empty record of task invocations; the clock carries
on from where the last test left it (it never goes backwards); processes started by earlier tests
are left alone — their tasks are never answered by the current test's mocks. Business keys are
unique per engine, so give each test its own, or pass `null` for a generated one.

The engine clock is one per JVM, so run these tests one at a time — JUnit's default. Do not turn on
JUnit's parallel execution (`junit.jupiter.execution.parallel.enabled`) or Surefire's `parallel` for
test classes that use the kit; to spread them over cores, parallelise by JVM instead — Surefire's
`<forkCount>1C</forkCount>` with `<reuseForks>true</reuseForks>` gives each fork its own JVM (and so
its own clock), with the classes of one fork still running one after another.

## Moving time

| Call | What it does |
|---|---|
| `advanceTime(Duration)` | Moves the clock forward, stopping at every due moment on the way and letting the engine act there |
| `advanceTimeTo(LocalDateTime)` | The same, to an absolute moment in the engine's zone |
| `runUntilIdle()` | Runs every scheduler pass at the current time until nothing changes (rarely needed: every other call already does) |
| `now()` / `clock()` | The engine's current time, and the `TestClock` itself |

A **TIMER** fires at its due moment. A **timeout** expires strictly after its limit, so it fires one
millisecond later: a step with a one-hour timeout is still waiting at exactly sixty minutes and has
timed out after sixty-one. **Retry backoffs** use the engine's real backoff policy
(`workflow.retry.backoff-*`, jitter included), and each dispatch records the time it happened, so a
test can measure them:

```java
runtime.whenTask("charge-card").thenFail("GATEWAY_DOWN").thenFail("GATEWAY_DOWN").thenComplete();

var charge = runtime.start("flaky-charge", "C-1", Map.of());
runtime.advanceTime(Duration.ofMinutes(1));

var calls = runtime.invocationsOf("charge");
assertThat(Duration.between(calls.get(0).at(), calls.get(1).at()))
        .isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));    // 1 s ± 20 % jitter
```

**Loops** are skipped the same way: a `repeat` or `loopBack` with a `delay` parks its next run until
the delay is over, and `advanceTime` stops there. Each run is its own step execution, so
`assertThat(process).stepExecutions("poll").hasSize(3)` counts the runs of a repeated step; each
`forEach` instance is dispatched — and mocked, or answered with `completeTask` — on its own.

**Cron schedules** fire once per occurrence on the way: `advanceTime(Duration.ofDays(3))` over a
`0 0 9 * * *` schedule starts three processes, each at 09:00. Find them with
`runtime.processes("nightly-report")`. **Retention purges** run too, when they are enabled.

## Mocking tasks

| Mock | Matches |
|---|---|
| `whenStep("charge")` | the step with that id — wins over everything else |
| `whenTask("charge-card")` | a step whose `task` is `charge-card` (any version), or — for a step with no `task` — whose id is `charge-card`, or whose `topic` is `charge-card` |

Each mock is a list of answers, consumed in order; the last one repeats:

| Answer | Effect |
|---|---|
| `thenComplete()` / `thenComplete(Map)` | The task completes, writing the variables onto the process (non-text values as JSON) |
| `thenFail(code)` / `thenFail(code, retryable)` | The task fails with `code`, as a `TaskFailure` would: a retryable failure spends the step's `retries`, a final one fails it at once |
| `thenTimeout()` | The worker never answers: the step waits until its `timeout` fires under `advanceTime` |
| `thenAnswer(invocation -> TaskOutcome...)` | Whatever the function decides from what the task was sent (`invocation.variable("amount")`, `invocation.attempt()` …) |

A task nothing mocks is answered by the application's **real `TaskHandler`**, when the test boots
one (see below); otherwise it waits, like a step whose worker has not answered yet. Answer it by
hand with `completeTask(process, stepId, variables)` or `failTask(process, stepId, code, retryable)`.
A `USER_TASK` always waits unless mocked; complete it with
`completeUserTask(process, stepId, submittedValues)`.

`invocations()` and `invocationsOf(stepId)` list every dispatch the test saw — process, step, task
id, topic, attempt, the variables it was sent and the engine time it happened.

### Agents

An `AGENT_CALL` never calls a model in a test: `whenAgent(stepId)` scripts its turns, and its tools are
answered by the stubs above — see [Agents with Tools](/guides/agents-with-tools/#testing-an-agent).

```java
var model = runtime.whenAgent("triage")
        .thenCallTool("lookupOrder", Map.of("orderId", "O-1"))
        .thenAnswer("{\"decision\":\"none\"}");
runtime.whenHttpCall("triage.lookupOrder").thenRespond(200, "{\"status\":\"DELIVERED\"}");

assertThat(runtime.start("support-triage", "T-1", Map.of("customerId", "C", "message", "m"))).isCompleted();
assertThat(model.lastToolResults()).hasSize(1);
```

### HTTP and LLM calls

An `HTTP_CALL` or `LLM_CALL` step never reaches the network in a test: without a stub it fails at
once, with a reason that names the stub to add.

```java
var payments = runtime.whenHttpCall("charge")
        .thenRespond(503, "busy")                                  // retried: 5xx is in retryOn
        .thenRespond(201, "{\"id\":\"ch_42\"}");

var process = runtime.start("http-charge", "H-1", Map.of("bookingId", "B-1", "amount", 99.5));
runtime.advanceTime(Duration.ofMinutes(1));

assertThat(process).isCompleted().hasVariable("chargeId", "ch_42");
assertThat(payments.lastRequest()).containsEntry("method", "POST");
```

The request is rendered by the engine exactly as in production, and the stubbed response goes
through the step's own `successStatus`, `retryOn` and `output` mapping — a `404` fails the step for
good, a `503` or `thenIoError()` is retried. `whenLlmCall("triage").thenAnswer("{\"category\":\"noise\"}")`
does the same for a model: `outputVariable`, `usageVariable` and `output` expressions over `text`,
`json`, `usage` and `model` are applied (the `responseSchema` itself is not validated).

## Messages, events and replies

| Call | What it does |
|---|---|
| `sendMessage(name, correlationKey, variables)` | Delivers a message, as `MessageReceived` or `POST /workflow/api/messages` would |
| `publishEvent(destination, type, data)` | Delivers a domain event as if it arrived on its topic: starts the processes whose `triggers` match and resumes the steps whose `onEvent` does |
| `publishedEvents(process)` | What the process's `PUBLISH_EVENT` steps published |
| `cancel(process)` | Cancels it, as an operator would |
| `process(businessKey)` / `processes(definitionId)` | Finds processes the test did not start itself (triggers, cron, children) |

## Assertions

`import static io.mateu.workflow.test.WorkflowAssertions.assertThat;` — every assertion reads
the process from the engine when it runs, and a failure prints its steps and variables.

| Assertion | Passes when |
|---|---|
| `isCompleted()` / `isCompensated()` / `isCompensationFailed()` / `isFailed()` / `isCancelled()` / `isRunning()` / `hasStatus(s)` | The process has that status |
| `hasPassedSteps("a", "b")` / `hasNotPassedSteps(...)` | Each step completed (or none did) |
| `isWaitingAt("step")` | The process is running and the step is in flight: a TIMER or message wait, an unanswered task, a retry's backoff, a lock |
| `hasStepStatus("step", StepExecutionStatus.TIMEOUT)` | The step's latest execution has that status |
| `stepExecutions("step").hasSize(n)` | Any AssertJ list assertion on the step's executions |
| `hasVariable(name, value)` / `hasVariables(map)` / `hasVariable(name)` / `doesNotHaveVariable(name)` | Variables are text in the engine: a text value is compared as text, anything else as the JSON it is stored as (`hasVariable("nights", 3)`) |
| `hasReplied()` / `hasReplied(json)` / `hasReplyOutcome(outcome)` | A `REPLY` step answered (compared as JSON), or the engine answered for the process |
| `hasPublishedEvent(type)` | A `PUBLISH_EVENT` step published an event of that type |

A retried step is one step execution with several attempts, so `stepExecutions("charge")` has one
element after two retries; count dispatches with `runtime.invocationsOf("charge")`.

## Running your real task handlers

By default the test boots the engine alone. To have your `TaskHandler`s answer the tasks no mock
does, boot them too — they run in-process through the worker SDK, exactly as in an embedded
application:

```java
@EventConductorTest(classes = {WorkflowTestApplication.class, PaymentHandlers.class})
class PaymentsTest { ... }
```

or `classes = MyApplication.class` for your own `@WorkflowEmbeddedApplication`. A mock always wins
over a handler, so a test can replace one dependency and keep the rest real.
(`worker-embedded` must be on the test classpath, as it is in any embedded application with handlers.)

## Properties

`@EventConductorTest(properties = {...})` adds Spring properties on top of the kit's. Useful ones:

| Property | Why |
|---|---|
| `workflow.retry.backoff-jitter=0.0` | Exact retry moments, for tests that assert them |
| `workflow.cron-enabled=false` | No cron starts at all in this test context |
| `workflow.processes.purge.*` | Exercise retention under `advanceTime` |

## How it differs from the test worker

The [test worker](/guides/test-worker/) and the test kit answer the same question —
*what does this workflow do when a task fails, hangs or answers late?* — at different levels.

| | `workflow-test` (this page) | Test worker |
|---|---|---|
| Where it runs | Inside a JUnit test, in memory | A deployed service, against a real orchestrator, broker and database |
| How scenarios are stated | Java mocks per test | A `TEST_CONFIG` JSON variable on the process |
| Time | A test clock: days pass in milliseconds | Real time: `durationMs` waits really |
| What it tests | Your definitions' logic: branches, timeouts, retries, compensation, replies | The whole deployment: transport, persistence, recovery, the UI |
| HTTP / LLM calls | Stubbed, never leave the test | Real calls through the built-in workers |
| Assertions | AssertJ, on the process | You look at the process (UI, API, MCP) |

Use the kit in your build, for every definition; use the test worker to demonstrate and check a
running deployment.
