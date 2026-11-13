---
title: "Workshop: a hotel booking, step by step"
description: A hands-on workshop — build a real business process with EventConductor, one module at a time, from a first flow to a booking with several rooms, failures, an HTTP API, a language model, a person in the loop, operations, debugging, automated tests and changes without a redeploy.
---

In this workshop you build one business process from scratch and grow it, module by module, the way a real one grows: **a hotel booking with several rooms**. A guest asks for three rooms; the hotel's system needs one booking per room; one of them may be sold out, the hotel's API may be slow, a manager may have to approve, and the guest has a special request a language model should understand.

Every module is short, runs on your laptop, and ends with something you can **see** in the console and **check** in a test. The screenshots on these pages were taken from the workshop's own project, running exactly as described.

## What you need

- **Java 21** and **Maven 3.9+**
- **Git**
- A browser
- About **2–3 hours** for everything; each module stands on its own if you want to stop.

Nothing else: no Docker, no database, no broker, no API key. The project brings an in-memory database, a fake hotel API and a fake language model, so nothing leaves your machine.

## The project

The finished project is in the repository, in [`examples/workshop-hotel-booking`](https://github.com/miguelperezcolom/eventconductor/tree/main/examples/workshop-hotel-booking). Each module explains the piece it adds; the project holds all of them side by side, one definition per module, so you can run any module at any time.

```text
workshop-hotel-booking/
├── pom.xml                          # the engine, the console, the built-in workers, the test kit
├── src/main/java/com/example/hotel/
│   ├── HotelBookingApplication.java # the app: engine + console in one Spring Boot app
│   ├── workers/HotelTaskHandlers.java  # the hotel's business logic (the ACTION steps)
│   └── fakes/                       # a fake hotel API and a fake OpenAI-compatible model
├── src/main/resources/
│   ├── application.properties
│   └── workflows/                   # one definition per module: 01-…yaml to 06-…yaml
├── src/test/java/…                  # the test-kit tests of Part 9
├── src/test/flows/                  # test-only definitions
├── scripts/start.sh                 # starts a process over the REST API
└── ci/github-actions-workflows.yml  # the CI pipeline of Part 9
```

## The modules

| # | Module | You learn |
|---|---|---|
| 0 | [Setup](/workshop/00-setup/) | Build, run, open the console |
| 1 | [A first flow](/workshop/01-first-flow/) | Definitions, steps, workers, starting a process, the graph |
| 2 | [One booking per room](/workshop/02-one-booking-per-room/) | `forEach`, an inline body, labels, lanes in the console |
| 3 | [When things fail](/workshop/03-when-things-fail/) | Retries, timeouts, compensation, *all rooms or none* |
| 4 | [Calling the hotel](/workshop/04-calling-the-hotel/) | `HTTP_CALL`, connections, idempotency keys |
| 5 | [Calling an AI](/workshop/05-calling-an-ai/) | `LLM_CALL`, structured output, the `AGENT` step |
| 6 | [A person in the loop](/workshop/06-a-person-in-the-loop/) | `USER_TASK`, escalation, `CHOICE`, completing a step |
| 7 | [Operating](/workshop/07-operating/) | Search, retry from a step, batch operations, MCP |
| 8 | [Debugging a workflow](/workshop/08-debugging/) | Errors, the step history, *Copy state*, logs, reproducing in a test |
| 9 | [Testing, automated](/workshop/testing/) | The test kit, time travel, simulated integrations, CI that runs it all on every change |
| 10 | [Changing without redeploying](/workshop/10-changing-without-redeploying/) | Git import, versions side by side, migrating safely |

If you only have an hour: modules 0–3, then [Part 9](/workshop/testing/). That is the core: a process, its failure handling, and the tests that keep it right.
