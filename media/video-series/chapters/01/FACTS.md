# Chapter 1 — facts checked

| Claim | Source |
|---|---|
| The booking: a manager's approval (USER_TASK + form), a CHOICE, one HTTP call per room with retries, timeout and an idempotency key | WS `06-hotel-booking.ec.yaml` (`approve`, `decision`, `rooms`); docs/workshop/06-a-person-in-the-loop.md |
| A room the hotel refuses does not cancel the others: the booked rooms stay booked, the hotel offers another, the guest decides | `06-hotel-booking.ec.yaml` / `04-rooms-booking-http.ec.yaml` (`onIterationFailure: continue`, `find-alternatives`, `ask-guest`, `wait-for-guest`); recorded live (BOOKING-7 / BOOKING-5 wait at `wait-for-guest` with nothing compensated) |
| The compensation cancels the booked rooms only if the guest cancels (END `compensate: true`), newest first → COMPENSATED | CHANGELOG *Unreleased* (Added, line 11); reference/step-types.md `#compensate`; scenario "the guest cancels, so the room that was booked is cancelled at the hotel" (`src/test/scenarios/hotel-booking.yaml`); recorded live (`answer.sh … cancel` → COMPENSATED, hotel holds nothing for it) |
| No answer: a reminder after a day, then the front desk — never an automatic cancel | `06-hotel-booking.ec.yaml` (`wait-for-guest` P1D → `remind-guest`, `wait-after-reminder` P1D → `front-desk`); `TimeTest.aGuestWhoNeverAnswersIsRemindedThenTheFrontDeskCalls` |
| Workers are Spring beans; return to complete, throw `TaskFailure` to fail; they never retry or roll back themselves | `HotelTaskHandlers.java` (class javadoc, `createRoomBooking()`) |
| Embedded in a Spring Boot app, JPA on H2; started over REST | WS `application.properties` (`workflow.mode=embedded`, `workflow.persistence=jpa`); `scripts/start.sh` |
| Operating: search by business key, Errors tab, retry from failure, batch operations | CL 2.30.0, 2.41.0; docs/guides/operating-running-processes.md |
| Tests in memory with a controlled clock; scenarios as YAML; `eventconductor:verify` coverage floors | WS `pom.xml` (minStepCoverage 90, minBranchCoverage 75); guides/testing-workflows.md; `mvn verify` (steps 98.7 %, branches 82.1 % on the guest-decides definitions) |
| Same definitions embedded, distributed (Kafka), Helm | guides/deployment-modes.md; `charts/eventconductor` |
| LLM_CALL with a response schema; stand-in model; MCP is opt-in | WS `05-special-request.ec.yaml`; `FakeLanguageModel.java`; guides/mcp-overview.md |

**Unreleased:** `compensate: true` ships in the release after 2.41.0 — record after it, or label it *upcoming*.
