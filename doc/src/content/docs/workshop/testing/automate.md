---
title: "9f · Automate it"
description: Validate every definition, run every workflow test, check that running processes can move onto the new version and that the tests reach every branch — with one command, on every pull request, so a broken definition never reaches the engine.
---

Tests nobody runs protect nothing. This page sets the workshop project up so that **every change is checked automatically**, and shows each check catching a real mistake.

## Four checks, one command

| Check | Goal | Catches |
|---|---|---|
| **Validate** | `eventconductor:validate` | A definition that does not parse, a dangling reference, a step nothing can ever start, an invalid expression |
| **Test** | the tests (`mvn verify` runs them) | A definition that is valid but does the wrong thing: a lost compensation, a wrong failure policy, a broken branch |
| **Check migration** | `eventconductor:check-migration` | A change that would strand the processes already running the previous version |
| **Coverage** | `eventconductor:verify` | A branch, a timeout route or a compensation no test ever takes |

`eventconductor:verify` runs `validate`, `check-migration` and the coverage floors in one goal, so CI needs **one command**:

```bash
mvn verify -Deventconductor.migration.from=origin/main
```

The workshop's `pom.xml` binds both goals — `validate` at `process-resources`, so a broken definition stops the build before any test runs, and `verify` at `verify`, after the tests, when the coverage they recorded is there to check:

```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <version>${eventconductor.version}</version>
  <executions>
    <execution>
      <goals><goal>validate</goal></goals>
    </execution>
    <execution>
      <id>eventconductor-verify</id>
      <goals><goal>verify</goal></goals>
      <configuration>
        <skipValidation>true</skipValidation>   <!-- validate already ran, before the tests -->
        <minStepCoverage>90</minStepCoverage>
        <minBranchCoverage>75</minBranchCoverage>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Without `-Deventconductor.migration.from` the migration check is skipped; without floors, the coverage is reported and nothing more. `mvn eventconductor:verify` on its own runs the three checks against the last test run — validation included, since the `skipValidation` above belongs to the bound execution.

## Each check, catching something

**A typo in a reference** — `preconditionStepId: register-bookin` in `01-first-booking.yaml`:

```text
$ mvn eventconductor:validate
[ERROR] …/workflows/01-first-booking.yaml:
[ERROR]   - Step 'done' references unknown precondition step 'register-bookin'.
[INFO] BUILD FAILURE
```

**A compensation that lost its link** — remove `compensable`/`compensationStepId` from module 3's `rooms`:

```text
$ mvn verify
[ERROR] …/workflows/03-rooms-booking-safe.yaml:
[ERROR]   - Step 'cancel-room-booking' has no preconditions and is not a START, a WAIT_FOR_MESSAGE
           or another step's compensation — nothing would ever start it.
[INFO] BUILD FAILURE
```

**A change of behaviour** — `onIterationFailure: continue` instead of `compensateAll`. Valid, so only a test can catch it:

```text
$ mvn verify
[ERROR] FailurePathsTest.compensateAllCancelsEveryRoomThatWasBooked:34 Expected process … (rooms-booking-safe, businessKey FAIL-1) to be COMPENSATED but it is COMPLETED.
[ERROR] ScenariosTest.scenario:16 [scenario 'a sold-out penthouse undoes the rooms that were booked' (src/test/scenarios/rooms-booking.yaml)] Expected process … to be COMPENSATED but it is COMPLETED.
…
[ERROR] Tests run: 21, Failures: 6, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

(What that message tells you: [Debugging a failing test](/workshop/testing/debugging-a-failing-test/).)

**A rename that strands running processes** — rename module 6's `approve` step to `manager-approval`. The definition is valid and the tests can be updated, but bookings that are *waiting for approval right now* would have nowhere to go:

```text
$ mvn verify -Deventconductor.migration.from=HEAD
[ERROR] EventConductor verify failed:
[ERROR]
[ERROR] [check-migration] EventConductor migration check HEAD → current: 6 workflow(s), 1 error(s), 0 warning(s).
[ERROR] hotel-booking: ERROR [approve]: step 'approve' (USER_TASK) has no compatible counterpart:
        a process waiting in it could not be migrated
```

`check-migration` compares two versions of the definitions — `-Deventconductor.migration.from` takes a git ref, a path, or `current` — and fails when a step a process can be *waiting in* has no counterpart. If the rename is deliberate, a mapping file says so (`-Deventconductor.migration.mapping=migration.yaml`, `hotel-booking: { approve: manager-approval }`). The goal also runs alone, as `mvn eventconductor:check-migration -Dfrom=HEAD`. See [Migrating running processes](/guides/migrating-running-processes/#in-ci-check-migration).

**A path no test takes** — the tests pass, but nothing ever makes module 4's hotel API refuse a room, so its compensation, `cancel-room`, has never run in a test. Delete the last scenario of `src/test/scenarios/rooms-booking.yaml` and:

```text
$ mvn verify
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] EventConductor workflow coverage: steps 45/47 (95.7%), branches 4/6 (66.7%), edges 34/36 (94.4%)
[ERROR] EventConductor verify failed:
[ERROR]
[ERROR] [coverage] branches coverage is 66.7%, below the minimum of 75.0%
[ERROR] Not covered:
[ERROR]   rooms-booking-http: step cancel-room, compensation rooms → cancel-room
[ERROR]   rooms-booking-tolerant: step cancel-room-booking, compensation rooms → cancel-room-booking
```

Every test of the kit records which steps its processes ran and which routes they took; after the tests, `target/eventconductor-coverage/` holds the result — `coverage.txt` to read, `coverage.json` for the goal:

```text
EventConductor workflow coverage — 21 test(s), 11 definition(s)

definition                    steps              branches           edges
hotel-booking                 8/8 (100.0%)       3/3 (100.0%)       7/7 (100.0%)
rooms-booking-http            4/4 (100.0%)       1/1 (100.0%)       3/3 (100.0%)
rooms-booking-tolerant        3/4 (75.0%)        0/1 (0.0%)         2/3 (66.7%)
…
TOTAL                         46/47 (97.9%)      5/6 (83.3%)        35/36 (97.2%)

Not covered:
  rooms-booking-tolerant
    step cancel-room-booking
    compensation rooms → cancel-room-booking
```

*Branches* are the routes that are decisions: CHOICE branches, guarded links, timeout routes, compensations and loop-backs. The one left here is deliberate — `continue` tolerates the sold-out room, so in that flow nothing is ever undone — which is why the floor is 75% and not 100%. See [Workflow coverage](/guides/testing-workflows/#workflow-coverage).

With everything fixed, all four pass:

```text
$ mvn verify -Deventconductor.migration.from=HEAD
[INFO] EventConductor: 6 definition(s) validated successfully.
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] EventConductor migration check HEAD → current: 6 workflow(s), 0 error(s), 0 warning(s).
[INFO] EventConductor workflow coverage: steps 46/47 (97.9%), branches 5/6 (83.3%), edges 35/36 (97.2%)
[INFO] EventConductor verify: every check passed.
[INFO] BUILD SUCCESS
```

## Cases anyone can add: scenarios

A test case does not have to be Java. `src/test/scenarios/` holds scenarios written as data — what to start, how each task and HTTP call answers, what happens next and how it must end — and `ScenariosTest` runs every one of them as a test of its own:

```java
@EventConductorTest
class ScenariosTest {

    @ParameterizedTest(name = "{0}")
    @ScenarioSource("src/test/scenarios")
    void scenario(Scenario scenario, WorkflowTestRuntime runtime) {
        runtime.run(scenario);
    }
}
```

So the person who knows the business — *"what if the manager rejects it?"* — adds a case to `src/test/scenarios/hotel-booking.yaml`:

```yaml
workflow: hotel-booking
variables:
  guest: Erin
  rooms: [ { number: "101", type: double }, { number: "102", type: double } ]
http:
  rooms: { status: 201, body: { id: HB-1 } }
  cancel-room: { status: 204 }

scenarios:
  - name: a rejected booking books nothing
    then:
      - completeUserTask: { step: approve, values: { approved: "false" } }
    expect:
      status: COMPLETED
      passedSteps: [rejected]
      notPassedSteps: [rooms, notify-guest]
      invocations: { rooms: 0 }
```

The file's settings are shared by its scenarios, and a scenario adds to them or overrides them. An answer can depend on what the task was sent — the first answer whose `when` holds is the one given, and `${…}` in a completion is filled in from the task's variables:

<!-- ec-example: skip -->
```yaml
steps:
  create-room-booking:
    - { when: "room.type == 'penthouse'", fail: SOLD_OUT, retryable: false }
    - { complete: { roomBookingId: "RB-${room.number}" } }
```

A failing scenario fails like any test, with its name and file in the message; scenarios count towards the coverage like any test. The whole format: [Scenarios as data](/guides/testing-workflows/#scenarios-as-data).

## The pipeline

The workshop project ships the GitHub Actions workflow in `ci/github-actions-workflows.yml`. In a repository of your own it goes in `.github/workflows/workflows.yml`:

<!-- ec-example: skip -->
```yaml
name: Workflows CI

on:
  pull_request:
  push:
    branches: [main]

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0                       # check-migration reads the previous version from git

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: maven

      - name: Validate, test, check migrations and coverage
        run: mvn -B verify -Deventconductor.migration.from="$FROM"
        env:
          FROM: ${{ github.event_name == 'pull_request' && format('origin/{0}', github.base_ref) || 'HEAD~1' }}

      - name: Keep the coverage report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: workflow-coverage
          path: target/eventconductor-coverage/
```

On a pull request, migrations are checked against the branch it targets; on `main`, against the previous commit. If what is deployed can lag behind `main`, tag the deployed commit and pass that tag instead.

## From a pull request to the engine

Put it together with the Git import of [module 10](/workshop/10-changing-without-redeploying/), and a change to a process travels like any code change:

1. **A branch, a pull request.** The reviewer reads the definition — and its graph in the IDE — in the diff.
2. **CI runs the four checks**, in one `mvn verify`. Red means it cannot merge.
3. **Merge to `main`.** The branch is protected: *Settings → Branches → require the "verify" check to pass*.
4. **The repository's webhook tells the engine**, which imports `main`, records a new version, and starts new bookings on it. Bookings already running keep their version, or are migrated deliberately.

**The protected branch plus the pipeline is what keeps a broken definition out of production**: the engine only ever imports what `main` holds, and nothing reaches `main` without passing validation, the tests, the migration check and the coverage floors.

## The kit or the test worker?

The kit tests your definitions' **logic**, in the build. The [test worker](/guides/test-worker/) is a deployed service that answers tasks according to a scenario in the process's variables — use it to check a **running deployment**: transport, persistence, recovery, the console.

| | `workflow-test` (this part) | Test worker |
|---|---|---|
| Where it runs | Inside a JUnit test, in memory | A deployed service, against a real engine, broker and database |
| How scenarios are stated | Java mocks per test, or [scenario files](#cases-anyone-can-add-scenarios) | A `TEST_CONFIG` JSON variable on the process |
| Time | A test clock: days pass in milliseconds | Real time |
| What it tests | Branches, timeouts, retries, compensation | The whole deployment |
| HTTP / LLM calls | Stubbed, never leave the test | Real calls through the built-in workers |
| Assertions | AssertJ, on the process | You look at the process (console, API, MCP) |

Use the kit in CI, for every definition; use the test worker in a test environment, after deploying.
