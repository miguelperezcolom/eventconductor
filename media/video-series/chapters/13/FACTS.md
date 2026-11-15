# Chapter 13 — facts checked

| Claim | Source |
|---|---|
| Two properties pick the topology; no code changes | docs/guides/deployment-modes.md:6, :10-11; workshop application.properties:4-12 |
| Memory for tests/demos; JPA survives restarts | deployment-modes.md:13, :47 |
| kafka mode: an orchestrator owns each process (process = message key); workers on topics, any language | deployment-modes.md:133-155; docs/reference/kafka-topics.md |
| HTTP_CALL, LLM_CALL, PUBLISH_EVENT served by the orchestrator's built-in workers (2.35) | CL 2.35.0 (:155); apps/docker-compose.yml orchestrator comment |
| Distributed stack from apps/docker-compose.yml, orchestrator 2.42.0, workshop definition mounted unchanged | apps/docker-compose.yml + chapters/13/compose.2.42.0.yml (documented pins); real `docker compose … ps` |
| Same penthouse booking ends Compensated on the orchestrator; hotel calls via Kafka | recorded (BOOKING-13 COMPENSATED; stand-in hotel holds nothing) |
| Console behind a login (admin / `SECURITY_PASSWORD`, default changeme) | deployment-modes.md:252-254 |
| Topics outbox, upstream, http-calls, llm-calls, event-publications | recorded Redpanda Console; kafka-topics.md:12-25 |
| Helm chart deploys the same apps pinned to appVersion 2.42.0, with Postgres and Redpanda; refuses to render without a DB password | charts/eventconductor/Chart.yaml:6,13; values.yaml (`imageTag` blank → appVersion); real `helm lint` / `helm template` output |
| Definitions from Git, change without redeploy (chapter 11) | workshop/10-changing-without-redeploying.md |

Not recorded: a live `helm install` on kind/k3d (the machine was shared with the pilot renders; the
orchestrator alone took ~3.5 min to start under docker). The chapter shows `helm lint`/`helm template` only and
says nothing about an install it did not record.
