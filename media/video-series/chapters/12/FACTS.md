# Chapter 12 — facts checked (every terminal is a real run, cached in build/ch12/term)

| Claim | Source |
|---|---|
| Each test its own in-memory engine; stub steps (complete/fail/never answer); `advanceTime` | CL 2.29.0 (:342); workshop TimeTest.java:14-32 |
| Hotel that never answers: 3 attempts then given up | TimeTest.java:28-31 (`invocationsOf(...).hasSize(3)`, `isFailed`) |
| Two days → escalation fired; one more → approval times out, no room booked | TimeTest.java:34-47; `hasEscalated` CL 2.41.0 (:38) |
| Since 2.41: compensation and per-iteration assertions by label | CL 2.41.0 (:35-36); RealHandlersTest.java:31 (shown by a real `grep`) |
| Scenarios as data: first answer whose `when` holds; workflow/variables/expect; ScenariosTest runs each as a test | CL 2.41.0 (:32); workshop src/test/scenarios/rooms-booking.yaml:1-35; ScenariosTest.java |
| 21 tests, 9 scenarios | real `mvn -B test` output |
| Coverage of steps, branches, links for every definition the engine knows; "Not covered" list | CL 2.41.0 (:31); real `cat target/eventconductor-coverage/coverage.txt` |
| `eventconductor:verify` compares with the previous version in git; fails on a step a waiting process could not be migrated to | CL 2.41.0 (:33), 2.31.0 (check-migration); real run: `ERROR [approve]: step 'approve' (USER_TASK) has no compatible counterpart` |
| One command: validation, tests, migration check, coverage floors (90 % / 75 % in the pom); every check runs, the build fails once | CL :33; workshop pom.xml:137-160; real `mvn -B verify -Deventconductor.migration.from=HEAD` |
| CI: same command on every PR, base branch for the migration check, coverage report kept | workshop ci/github-actions-workflows.yml:17-48 |

Finding: `-Deventconductor.coverage.minBranches=95` (CL :33) has no effect in the workshop, whose pom sets
`<minBranchCoverage>` explicitly (plugin configuration wins over the user property). Not used in the chapter.
