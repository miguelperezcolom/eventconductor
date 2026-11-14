# Chapter 6 — facts checked (against v2.41.0)

| Claim | Source |
|---|---|
| CHOICE takes exactly one branch, the first whose guard holds; an unguarded branch is the default | `doc/.../reference/step-types.md` 634, 692; `group-arrival.ec.yaml` 12–28 |
| XOR join after a CHOICE, AND join after a FORK | `group-arrival.ec.yaml` 30–34, 78–82 |
| forEach over a named PROCESS, labelled; `onIterationFailure: continue` — the step completes | CHANGELOG 2.39.0 l. 77–78; filmed GROUP-1 (3/4, Room 304 Error, process Completed) |
| Diagram numbers steps in run order; skipped branch marked | filmed |
| `repeat` (until, maxIterations, delay) and `loopBack` (to, maxIterations) | `prepare-room.ec.yaml` 11–38; CHANGELOG 2.29.0 l. 336 |
| Double-click a forEach iteration of a PROCESS opens its child run | CHANGELOG 2.39.0 ("Double-click a PROCESS node to open its child run") |
| Inline body: versioned with the parent, cannot be started on its own | CHANGELOG 2.39.0 l. 76 |
| `compensateLosers` on an XOR join is opt-in, since 2.39; losing branches cancelled and compensated | CHANGELOG 2.39.0 l. 79; `retries-timeouts-compensation.md` 269 |
| City Cabs answers first (stand-in) | `examples/video-series/.../fakes/FakeTaxis.java` (DELAY_MS) |

Console quirk seen (not narrated): in Room 303's child run, *Fix what the inspection found* ran once (COMPLETED,
iteration 0) but is drawn as skipped, because its second pass was CANCELLED — see the PR's bug list.
