---
title: "9 · Testing, automated"
description: Part 9 of the workshop — test the hotel booking with the EventConductor test kit, from the happy path to failures, time, simulated integrations and the real handlers, and run it all automatically on every change.
---

A workflow definition is code: it has branches, deadlines, retries and a rollback path, and every one of them deserves a test. This part tests the hotel booking you built, with the **test kit** (`workflow-test`), and ends with the project set up so that **every change is checked automatically** — before it can reach the engine.

The kit runs **your definitions in the real engine, in memory**, inside a JUnit 5 test: no server, no broker, no database. It gives a test three things a running deployment cannot:

- **mocks instead of workers** — answer each task with a completion, a failure or silence;
- **a clock you move** — *three days* pass in milliseconds;
- **assertions on the process** — `assertThat(booking).isCompensated().hasPassedSteps("cancel-room-booking")`.

All the tests on these pages are in the workshop project, in `src/test/java`, and pass:

```text
$ mvn verify
[INFO] Tests run: 1, Failures: 0, Errors: 0 -- in com.example.hotel.HappyPathTest
[INFO] Tests run: 2, Failures: 0, Errors: 0 -- in com.example.hotel.FailurePathsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0 -- in com.example.hotel.RealHandlersTest
[INFO] Tests run: 3, Failures: 0, Errors: 0 -- in com.example.hotel.IntegrationsTest
[INFO] Tests run: 2, Failures: 0, Errors: 0 -- in com.example.hotel.TimeTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Page | What it tests |
|---|---|
| [The happy path](/workshop/testing/happy-path/) | Every room booked, the forEach's collected output |
| [When things fail](/workshop/testing/failure-paths/) | A sold-out room: `compensateAll` vs `continue` |
| [Time](/workshop/testing/time/) | A worker that never answers; an approval nobody gives for three days |
| [Simulated integrations](/workshop/testing/integrations/) | The hotel's API, the language model and an agent — and the idempotency key actually sent |
| [The real handlers](/workshop/testing/real-handlers/) | Your Java handlers in the loop, and a mock that still wins |
| [Automate it](/workshop/testing/automate/) | Validate, test and check migrations in CI, on every pull request |
| [Debugging a failing test](/workshop/testing/debugging-a-failing-test/) | Reading what a failing assertion prints |

## Set up

One dependency — it brings the engine, JUnit 5, Spring's test support and AssertJ:

```xml
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-test</artifactId>
  <version>${eventconductor.version}</version>
  <scope>test</scope>
</dependency>
```

A test class is annotated `@EventConductorTest` and receives a `WorkflowTestRuntime`. It boots the engine in **embedded mode with memory persistence**, loads the definitions from `classpath:/workflows/` — the same files the application runs — and answers every task on the calling thread, so there is nothing to wait for.

Test-only definitions go in a folder of their own, loaded with `@EventConductorTest(definitions = "src/test/flows")`. `src/test/resources/workflows/` works too — the engine reads the `workflows` directory of every classpath root, main and test — but a file there with the same id as one of the application's **replaces** it in every test (test output is first on the classpath), which is easy to forget. A folder of its own keeps the application's definitions the ones under test.
