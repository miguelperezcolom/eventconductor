# EventConductor promo — script

90 seconds, 1920×1080, 30 fps. It is made to be watched **muted**: everything that matters is
on screen. This script adds a **voice-over** for a narrated cut (YouTube, talks). Read it at an
easy pace — each block fits its scene with room to breathe; where a line runs long, cut the
words in *italics* first.

On-screen text lives in [`strings.json`](strings.json). To make another language, copy it
(`strings.es.json`), translate the values and render with
`node scripts/render-animated.mjs --strings strings.es.json`.

| # | Time | On screen | Voice-over |
|---|---|---|---|
| 1 | 0:00–0:06 | "Your process is three steps." — *Book each room · Get approval · Confirm to the customer* | Your business process is three steps. Book the rooms, get it approved, confirm to the customer. |
| 2 | 0:06–0:18 | The water rises under the three steps; sixteen red chips sink below it, one by one. "Production adds the rest." | Then production adds the rest. Undo what already succeeded when a later step fails. Retries that must not book twice. A pod that dies halfway. Replies that arrive after you gave up. People who answer two days later. *Code that changes while runs are in flight.* |
| 3 | 0:18–0:23 | "The code that never fails is the code you don't write." The red chips turn green: *Built into EventConductor*. | The code that never fails is the code you don't write. With EventConductor, all of that is built in. |
| 4 | 0:23–0:38 | The definition is typed in the editor and validated; the console draws its graph and opens the inline body. | You declare the process. One booking per room — and if one room fails, all of them are undone. The graph comes from the definition. *Open the inline body: one lane per room.* |
| 5 | 0:38–0:55 | A real run in the console: three rooms, room 102 refused, 101 and 103 compensated, status COMPENSATED. | Here is a real run. Three rooms. The hotel refuses room 102 — so EventConductor cancels 101 and 103 on its own. Nothing is left booked, and every step of it is on screen. |
| 6 | 0:55–1:05 | Search by business key; select the failed run; *Retry from failure*. An MCP client asks to retry what failed. | Find any run by its business key. Retry it from where it failed — or just ask your AI assistant to do it. |
| 7 | 1:05–1:15 | `mvn test` green → the failure policy is changed → `mvn test` red → the pull request's checks block the merge. | Every change runs the workflow's tests. Switch the failure policy by mistake and the build goes red — a broken saga never reaches production. |
| 8 | 1:15–1:23 | Merge → Git webhook → engine imports v2; the Versions tab shows v1 and v2 side by side. | Ship it by merging: the engine imports the new version. No service redeploy, and runs in flight keep the version they started with. |
| 9 | 1:23–1:30 | EventConductor · *Orchestration as a library · MIT* · eventconductor.io | EventConductor. Orchestration as a library. Open source, MIT. Start with the hands-on workshop at eventconductor.io. |

## What is real and what is illustrated

- **Real**: every console view (scenes 4–6 and 8) is a screenshot of the actual console running the
  `hotel-booking` definition in [`app/`](app) — including the failed lane, the compensation, the
  search, the selected failed run and the Versions tab with two versions. The YAML is the actual
  file (three trailing steps are elided on screen with a comment). Every terminal line in scenes 4
  and 7 is real output of `mvn eventconductor:validate` and `mvn test` on [`gate/`](gate)
  (`scripts/capture-terminal.sh`), trimmed to the lines that matter.
- **Animated over real**: the camera moves, the cursor, clicks, highlights and callouts.
- **Illustrated** (and labelled on screen): the MCP chat in scene 6 (the tool names and the reply
  text are the engine's own; the conversation is staged), the pull-request checks panel in scene 7
  and the merge → webhook → import strip in scene 8.
- **Animated**: the iceberg (scenes 1–3) and the outro.
