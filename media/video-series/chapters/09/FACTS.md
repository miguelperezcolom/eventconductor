# Chapter 9 — facts checked

Baseline v2.41.0. `CL` = CHANGELOG.md, `docs/` = doc/src/content/docs/, `SA` = examples/video-series.

| Claim (narration / screen) | Source |
|---|---|
| A trigger in a file beside the definitions starts a workflow on a CloudEvent of a type on a destination; business key and variables from `data` | docs/guides/domain-event-triggers.md:8, :34-41, :56-68; SA/src/main/resources/workflows/events.yaml:4-12; CL 2.26.0 (:473, :478) |
| Same event (same source + id) never starts two processes through one trigger | docs/guides/domain-event-triggers.md:125-131 (receipts, kept `workflow.events.receipts.retention`, 7 days) |
| Generic webhook gateway: HMAC signature of the body (`X-Signature-256`), CloudEvent with a fixed type, handed to the engine (`transport=rest`) | docs/guides/webhooks.md:39, :60-63, :79-80; SA application.properties:50-59; CL 2.26.0 (:480) |
| **Only on main:** the gateway embedded in the same app as the engine | CL [Unreleased] → Fixed (`WebhookDeliveryProvider`); flagged on screen (pill `webhook.main`) |
| "from GitHub or any sender" | providers `github`, `generic` (CL :480; modules/webhook-gateway) |
| Received events page: what each event did (Started, business key), why one could not be used | console `ReceivedEventRow.java` (Outcome, Business key, details row = reason); docs/guides/domain-event-triggers.md:176; recorded live |
| PUBLISH_EVENT: destination, type, key, payload from variables | docs/guides/publishing-events.md:13-20; SA booking-onboarding.ec.yaml:18-31 |
| Built-in worker since 2.35; topic `event-publications` when distributed; embedded → the app's publisher | CL 2.35.0 (:155, :160, :163); publishing-events.md:29, :54 |
| At least once; event id = step execution id | publishing-events.md:27 |
| Synchronous invocation is opt-in per definition, with a deadline | docs/guides/synchronous-invocation.md:50-55 (`enabled` default false, `defaultDeadlineMs`); CL 2.20.0 |
| REPLY answers with `replyVariables`; process carries on | synchronous-invocation.md:21, :72-83; SA room-quote.ec.yaml; recorded `processStatus: RUNNING` in the reply |
| Deadline passes first → 202 + link to read later | synchronous-invocation.md:72-83, :102 |
| Distributed: destinations are Kafka topics, definitions unchanged | docs/reference/kafka-topics.md:25; domain-event-triggers.md:136-147 |

Stand-ins labelled on screen: channel manager (`scripts/webhook.sh`), `GET /fake/events`.
