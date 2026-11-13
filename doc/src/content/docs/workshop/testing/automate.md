---
title: "9f · Automate it"
description: Validate every definition, run every workflow test and check that running processes can move onto the new version — on every pull request, so a broken definition never reaches the engine.
---

Tests nobody runs protect nothing. This page sets the workshop project up so that **every change is checked automatically**, and shows each check catching a real mistake.

## Three checks

| Check | Command | Catches |
|---|---|---|
| **Validate** | `mvn eventconductor:validate` — also part of `mvn verify` | A definition that does not parse, a dangling reference, a step nothing can ever start, an invalid expression |
| **Test** | `mvn verify` | A definition that is valid but does the wrong thing: a lost compensation, a wrong failure policy, a broken branch |
| **Check migration** | `mvn eventconductor:check-migration -Dfrom=<previous>` | A change that would strand the processes already running the previous version |

The plugin runs `validate` at `process-resources` because the workshop's `pom.xml` binds it:

```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <version>${eventconductor.version}</version>
  <executions>
    <execution>
      <goals><goal>validate</goal></goals>
    </execution>
  </executions>
</plugin>
```

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
[ERROR] FailurePathsTest.compensateAllCancelsEveryRoomThatWasBooked <<< FAILURE!
Expected process … (rooms-booking-safe, businessKey FAIL-1) to be COMPENSATED but it is COMPLETED.
[ERROR] Tests run: 11, Failures: 3, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

(What that message tells you: [Debugging a failing test](/workshop/testing/debugging-a-failing-test/).)

**A rename that strands running processes** — rename module 6's `approve` step to `manager-approval`. The definition is valid and the tests can be updated, but bookings that are *waiting for approval right now* would have nowhere to go:

```text
$ mvn eventconductor:check-migration -Dfrom=HEAD
[ERROR] EventConductor migration check HEAD → current: 6 workflow(s), 1 error(s), 0 warning(s).
[ERROR] hotel-booking: ERROR [approve]: step 'approve' (USER_TASK) has no compatible counterpart:
        a process waiting in it could not be migrated
```

`check-migration` compares two versions of the definitions — `-Dfrom` takes a git ref, a path, or `current` — and fails when a step a process can be *waiting in* has no counterpart. If the rename is deliberate, a mapping file says so (`-Dmapping=migration.yaml`, `hotel-booking: { approve: manager-approval }`). See [Migrating running processes](/guides/migrating-running-processes/#in-ci-check-migration).

With everything fixed, all three pass:

```text
$ mvn verify && mvn eventconductor:check-migration -Dfrom=HEAD
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] EventConductor migration check HEAD → current: 6 workflow(s), 0 error(s), 0 warning(s).
[INFO] BUILD SUCCESS
```

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

      - name: Validate the definitions and run the workflow tests
        run: mvn -B verify

      - name: Check that running processes can move onto this version
        run: mvn -B eventconductor:check-migration -Dfrom="$FROM"
        env:
          FROM: ${{ github.event_name == 'pull_request' && format('origin/{0}', github.base_ref) || 'HEAD~1' }}
```

On a pull request, migrations are checked against the branch it targets; on `main`, against the previous commit. If what is deployed can lag behind `main`, tag the deployed commit and pass that tag instead.

## From a pull request to the engine

Put it together with the Git import of [module 10](/workshop/10-changing-without-redeploying/), and a change to a process travels like any code change:

1. **A branch, a pull request.** The reviewer reads the definition — and its graph in the IDE — in the diff.
2. **CI runs the three checks.** Red means it cannot merge.
3. **Merge to `main`.** The branch is protected: *Settings → Branches → require the "verify" check to pass*.
4. **The repository's webhook tells the engine**, which imports `main`, records a new version, and starts new bookings on it. Bookings already running keep their version, or are migrated deliberately.

**The protected branch plus the pipeline is what keeps a broken definition out of production**: the engine only ever imports what `main` holds, and nothing reaches `main` without passing validation, the tests and the migration check.

## The kit or the test worker?

The kit tests your definitions' **logic**, in the build. The [test worker](/guides/test-worker/) is a deployed service that answers tasks according to a scenario in the process's variables — use it to check a **running deployment**: transport, persistence, recovery, the console.

| | `workflow-test` (this part) | Test worker |
|---|---|---|
| Where it runs | Inside a JUnit test, in memory | A deployed service, against a real engine, broker and database |
| How scenarios are stated | Java mocks per test | A `TEST_CONFIG` JSON variable on the process |
| Time | A test clock: days pass in milliseconds | Real time |
| What it tests | Branches, timeouts, retries, compensation | The whole deployment |
| HTTP / LLM calls | Stubbed, never leave the test | Real calls through the built-in workers |
| Assertions | AssertJ, on the process | You look at the process (console, API, MCP) |

Use the kit in CI, for every definition; use the test worker in a test environment, after deploying.
