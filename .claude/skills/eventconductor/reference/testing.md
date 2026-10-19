# Validating and testing definitions

Two layers, both in the normal Maven build: the **Maven plugin** checks every definition statically;
the **`workflow-test` kit** runs your definitions in JUnit 5 with the real engine in memory and a
clock you move. Canonical: `doc/src/content/docs/guides/testing-workflows.md`,
`reference/maven-plugin.md`.

## Validate: `workflow-maven-plugin`

```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <version>${eventconductor.version}</version>
  <executions>
    <execution><goals><goal>validate</goal></goals></execution>
  </executions>
</plugin>
```

- `mvn eventconductor:validate` on demand; bound to `process-resources`, so `mvn test`/`verify` run it.
- Reads `src/main/resources/{workflows,forms,rules,tasks}/**` (`.ec .ecform .ecrule .ectask .json .yaml .yml`),
  plus `workflows/cron.yaml` and `workflows/events.yaml`.
- Checks: the JSON schema (unknown keys are errors — a typo like `retires` fails), roots/START rule,
  dangling ids, cycles, JEXL parseability, loops, HTTP/LLM/agent blocks, triggers, schedules,
  retention, sensitive-variable rules, task contracts. Warnings (never fail): JOIN on a guarded step,
  CHOICE without a default, message fields on HTTP_CALL/PUBLISH_EVENT outside `awaitReply`.
- Only at engine load: TIMER `duration: 0`, blank `messageName`/`correlationExpression`, missing
  encryption keys.
- Properties: `eventconductor.validate.skip`, `.failOnError`, `.failOnMissing`, `.workflows|forms|rules|tasks`.
- Before shipping a new version of a running workflow:
  `mvn eventconductor:check-migration -Dfrom=<git ref|path|current> [-Dto=...] [-Dmapping=migration.yaml] [-Dworkflow=<id>]`
  fails when a step a process can be waiting in has no counterpart (see [operations.md](operations.md#migration)).

Output on failure:
```
EventConductor definition validation found 1 problem(s):
src/main/resources/workflows/order.yaml:
  - Step 's1' references unknown precondition step 'nope'.
```

## Test: `workflow-test`

```xml
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-test</artifactId>
  <version>${eventconductor.version}</version>
  <scope>test</scope>   <!-- brings the engine, JUnit 5, Spring test, AssertJ -->
</dependency>
```

`@EventConductorTest` (package `io.mateu.workflow.test`) is a `@SpringBootTest` booting the engine
embedded + memory with a test clock, schedulers driven by the kit, every task answered on the calling
thread (nothing to await). Definitions load from `classpath:/workflows/` (`src/main/resources/workflows`
or `src/test/resources/workflows`), plus `@EventConductorTest(definitions = {"src/test/flows"})` or
`runtime.deploy(...)`. Inject `WorkflowTestRuntime` as a test-method parameter.

### Saga with a timer

For the `booking-saga` definition of [patterns.md](patterns.md#1-saga-with-a-timer-and-compensation):

```java
package com.example;

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
    void capturesAfterTheCoolingOffPeriod(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete(Map.of("roomId", "R-12"));
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_1"));
        runtime.whenTask("capture-payment").thenComplete();

        var booking = runtime.start("booking-saga", "B-1", Map.of("amount", 120));
        assertThat(booking).isWaitingAt("cooling-off").hasVariable("chargeId", "ch_1");

        runtime.advanceTime(Duration.ofDays(3));                  // milliseconds of wall time
        assertThat(booking).isCompleted()
                .hasPassedSteps("reserve", "charge", "cooling-off", "capture");
    }

    @Test
    void compensatesWhenTheCaptureFails(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete();
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_2"));
        runtime.whenTask("capture-payment").thenFail("CARD_EXPIRED", false);   // final: no retries
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

Use a different business key per test (or `null` for a generated one): keys are unique per engine and
the Spring context (and its processes) is shared across tests.

### Mocks

| Call | Matches / does |
|---|---|
| `whenStep("charge")` | that step id (wins over everything) |
| `whenTask("charge-card")` | a step whose `task` is `charge-card`, else whose id or `topic` is `charge-card` |
| `.thenComplete()` / `.thenComplete(Map)` | completes, writing variables (non-text as JSON) |
| `.thenFail(code)` / `.thenFail(code, retryable)` | fails; retryable spends `retries`, final fails at once |
| `.thenTimeout()` | never answers; the step's `timeout` fires under `advanceTime` |
| `.thenAnswer(inv -> TaskOutcome.complete(...))` | decide from `inv.variable("x")`, `inv.attempt()` |
| chain `.thenFail("X").thenComplete()` | consecutive answers; the last repeats |
| `whenHttpCall("step").thenRespond(status, body)` / `.thenIoError()` | stubs HTTP_CALL (unstubbed calls fail); `.lastRequest()` |
| `whenLlmCall("step").thenAnswer(text)` | stubs LLM_CALL |
| `whenAgent("step").thenCallTool(name, args).thenAnswer(json)` | scripts AGENT_CALL turns; tool `x` of agent `a` stubbed as `whenHttpCall("a.x")` |

Unmocked tasks wait (or run your real `TaskHandler`s if booted: `@EventConductorTest(classes = {MyApp.class})`
with `worker-embedded`). Answer by hand: `completeTask(process, stepId, vars)`,
`failTask(process, stepId, code, retryable)`, `completeUserTask(process, stepId, values)` (USER_TASK
always waits unless answered; forms-engine not needed).

### Time, messages, events, updates

- `advanceTime(Duration)` / `advanceTimeTo(LocalDateTime)` — stops at every TIMER, timeout (+1 ms),
  retry backoff, repeat/loopBack delay, escalation, cron occurrence, flow-control window.
- `sendMessage(name, correlationKey, vars)`, `publishEvent(destination, type, data)` (starts triggers,
  resumes `onEvent` waits / `awaitReply`), `publishedEvents(process)`, `cancel(process)`.
- `update(process, name, payload)` → `UpdateOutcome`; `updateWithStart(defId, key, vars, name, payload)`.
- `process(businessKey)`, `processes(definitionId)` — processes the test did not start (triggers, cron, children).
- `invocations()`, `invocationsOf(stepId)` — every dispatch, with variables, attempt and engine time `at()`.

### Assertions (`WorkflowAssertions.assertThat(process)`)

`isCompleted()` `isCompensated()` `isCompensationFailed()` `isFailed()` `isCancelled()` `isRunning()`
`hasStatus(s)` · `hasPassedSteps(...)` `hasNotPassedSteps(...)` · `isWaitingAt(step)` ·
`hasStepStatus(step, StepExecutionStatus.TIMEOUT)` · `stepExecutions(step).hasSize(n)` ·
`hasVariable(name, value)` `hasVariables(map)` `doesNotHaveVariable(name)` · `hasReplied(json)` ·
`hasPublishedEvent(type)` · `hasAppliedUpdates(...)`. On an `UpdateOutcome`: `isCompleted()`,
`isRefusedWith(text)`, `hasResult(json)`…

### Rules of the kit

- Do not run kit tests in parallel in one JVM (one engine clock per JVM); use Surefire `forkCount`.
- Useful properties: `@EventConductorTest(properties = "workflow.retry.backoff-jitter=0.0")` for exact
  retry moments; `workflow.cron-enabled=false`.
- A retried step is ONE step execution with several attempts: count dispatches with `invocationsOf`.
