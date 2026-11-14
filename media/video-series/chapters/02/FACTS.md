# Chapter 2 — facts checked (against v2.41.0)

| Claim | Source |
|---|---|
| Two dependencies: `workflow-engine`, `builtin-workers` (HTTP/LLM/PUBLISH_EVENT + task handlers in process) | `examples/workshop-hotel-booking/pom.xml` 40–52; CHANGELOG 2.35.0 (built-in workers) |
| `workflow.mode=embedded`, `workflow.persistence=jpa`, H2 in memory | `examples/workshop-hotel-booking/src/main/resources/application.properties` 5–12 |
| Since 2.27 the API refuses anonymous callers; `allow-anonymous` is a laptop-only switch | CHANGELOG 2.27.0 l. 428/436; `application.properties` 14–18 |
| Definitions under `workflows/` on the classpath load at startup; `*.ec.yaml` is plain YAML | `doc/.../workshop/01-first-flow.md` ("every file under workflows/…"); CHANGELOG [Unreleased] *Documentation* (`*.ec.yaml` naming is docs-only, the engine always read `.yaml`) |
| No arrows: steps declare `preconditionStepId`, the graph is derived | `01-first-flow.md` ("There are no arrows to draw") |
| An ACTION with no `task:` is served by the handler with the step's id; input bound by name; output fields become variables | `HotelTaskHandlers.java` 14–16, 30–43; `01-first-flow.md` |
| Starting is idempotent on the business key; the first answer is the new process (PENDING), run in the background | `01-first-flow.md` ("Starting is idempotent on it"); shown live (`start-1` → PENDING, `start-1-again` → same id, COMPLETED) |
| Steps tab: started / finished per step; Variables has `bookingNumber`; Diagram numbers steps in the order they ran | the recorded console (Steps columns Name/Status/Started/Finished — no duration column) |

Recorded output: `boot` is the app's real startup log (the run `scripts/app.sh` started with `mvn spring-boot:run`), shown as that command.
