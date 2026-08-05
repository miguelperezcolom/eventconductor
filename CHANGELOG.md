# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Plugins — IntelliJ 0.1.3, VS Code 0.1.2
- **The editors ship the current graph.** Neither plugin's sources have changed since the last
  plugin release; what has changed is the component they carry, and they only carry a new one when
  they are released. Installed alongside `1.0-beta.022`, the editor was laying a definition out
  differently from the web viewer — it had the bundle from 4 August, before the rollback edges
  reached the layout. These builds bring compensation steps drawn amber once they have run, the
  rollback edges laid out (so a compensation sits to the right of the step it undoes rather than at
  the far left with its line drawn back across the graph), and a paused animation that stays paused
  when a node is selected.

## [1.0-beta.022] - 2026-08-05

A failure that says why.

An exception escaping an embedded worker has failed its step since the last release, and the
process recorded that it had failed and nothing about why — the reason existed only in the
application's stdout. Two separate things were dropping it, and the second one had been hiding
the first for as long as there has been an Errors tab: it was empty however badly a process had
gone, because it looked for a string nothing writes.

### Fixed
- **A failing step recorded that it failed, and nothing about why.** `UpdateStepExecutionCommand`
  has always carried a log line — a worker's message, or the exception the engine catches on its
  behalf in embedded mode — and the use case dropped it: the process log said "Task status changed
  to ERROR" and the reason existed only in the application's stdout. It is written to the process
  now, typed by outcome, so a failure lands where failures are read.
- **And where they are read was reading the wrong thing.** Log messages are stored as
  `MessageType.name()` — "Error" — and all four readers compared against the lowercase literal, so
  the Errors tab of a process was empty however badly it had gone, its errors appeared in the
  Messages tab instead, and the graph's hover card never had a reason to show. Matched
  case-insensitively now, which covers the rows already written.
- **Selecting a node started the animation the operator had paused.** Focusing belongs to the
  simulation — it picks the paths the token will take and lights them while the rest of the graph
  falls back — so on a paused graph a click now selects the node and leaves the picture neutral,
  clearing any focus left over from before the pause. The token itself was already staying
  stopped; it was the lighting that read as it starting again.

## [1.0-beta.021] - 2026-08-05

The mode we were the only ones running.

Kafka mode worked in the standalone application, in the benchmark and in the distributed tests —
everywhere we run the engine ourselves — and could not work anywhere else, because the one
property that makes its consumers function was in each of those configurations and in none of the
documentation. An application that followed the README to the letter got a `ClassCastException` on
the first event of its first process, and its whole outbox dead-lettered behind it. The engine
brings its own bindings now.

The rest is a compensated saga finally looking like what it is: a process that finished.

### Fixed
- **Kafka mode did not work outside our own applications.** The engine's consumers take a batch —
  `Consumer<Message<List<DomainEvent>>>` — because a poll batch is committed as one transaction
  per process. A binding without `consumer.batch-mode` delivers one record and does not convert
  it, so the payload arrives as a `byte[]` and the first event dies with `ClassCastException:
  class [B cannot be cast to class java.util.List`; retries exhaust and every outbox event is
  dead-lettered. That property was set in the standalone application's YAML, in the benchmark and
  in the distributed tests, and appeared in no documentation at all — every snippet in the README,
  the configuration reference, the AI references and the scaffold skill wired the destinations and
  left it out. Those same snippets gave both consumers one shared group, which is the
  range-assignor trap fixed for our own configuration in `1.0-beta.015` and never fixed in the
  docs. The engine contributes its own bindings now — destinations, a group per binding,
  batch-mode — as the lowest-precedence property source, so anything an application declares still
  wins. It does not contribute `spring.cloud.function.definition`: that lists the functions the
  application composes, and a default would silently drop a worker's or the forms engine's. Also
  `spring.cloud.stream.function.definition`, in the README and the scaffold skill: that property
  has not been read since Boot 2.x.
- **A rolled-back process looked like one that had stopped halfway.** Reaching `COMPENSATED` only
  set the status: the steps the flow never reached stayed `CREATED` — indefinitely, on a process
  that is over — and the completion bar stayed frozen wherever the failure happened. A finished
  saga went on showing steps that looked like they were waiting their turn, at 43%. The steps that
  can no longer run are cancelled now, the same ones an END transition cancels, and the process is
  100% complete: the rollback ran to the end. The step that failed keeps its `ERROR`.
- **`COMPENSATED` is drawn green**, like `COMPLETED`. It was amber, which said "something here
  needs looking at" about a process that had already cleaned up after itself, and sat it next to
  the `ERROR` processes that do.
- **The rollback edges were drawn but never laid out.** ELK was given the flow and nothing else,
  so a compensation step — which declares no preconditions — was a node with no edges at all: it
  went to the first layer, at the far left, and its rollback line was then drawn from the middle
  of the flow back across everything in front of it, through whatever nodes were in the way. The
  layout gets those edges now, which puts each compensation in the layer after the step it undoes
  — to its right, where the eye looks for it, stacked above or below the flow rather than beyond
  it — and routes around what is already there. Definitions written either way lay out the same.

### Added
- **The demo images are published from CI**, by a `workflow_dispatch` job that builds the demo
  reactor once and pushes all seven, each labelled with the revision it was built at. They used to
  be built by hand with buildx: the ones on Docker Hub had been pushed three hours before the
  Dockerfiles they came from were committed, and nothing recorded which commit any of them
  contained. Their tag is `demo-0.1.0` now — the demo does not ship with the engine, and a tag
  shaped like an engine version reads as stale the moment the engine moves.

### Fixed (build)
- **The demo did not build from a clean checkout.** Five of its services depend on
  `io.mateu.workflow:shared:1.0-SNAPSHOT`, which nothing publishes; on any machine that had built
  the repository it was in `~/.m2`, so it appeared to build on its own.

## [1.0-beta.020] - 2026-08-05

One worker is not the engine — and a step with nothing to wait for is not a step that may run.

Both came out of the same afternoon with a proof-of-concept saga. A hotel service that accepted
the connection and never answered stopped every process in the JVM, not just its own, and the
symptom named nothing: the processes created afterwards sat with every step in `CREATED`, which
the UI describes as "waiting for its preconditions". And the compensation steps of that saga ran
in the happy path, because an anchor written without its guard is an ordinary edge — an anchor
they only needed because "no preconditions" meant "start immediately".

### Fixed
- **An embedded worker that blocked stopped the engine, and one that threw stopped its process.**
  Embedded dispatch calls the worker and waits for it to return, on the calling thread — which
  under `jpa` persistence is `embedded-outbox-relay`, the single thread draining the outbox and so
  the only one advancing every process in the JVM. One HTTP call to a service that accepted the
  connection and never answered froze all of them: no step-over ran, and processes created
  afterwards sat with every step in `CREATED`, which the UI describes as "waiting for its
  preconditions" and which looks nothing like a stuck worker.

  And an exception escaping a worker left its `StepExecution` exactly as it was, `PENDING`,
  waiting for a reply that was never coming; only the outbox row was parked as `Error`. Without a
  `timeout` on the step nothing would ever look at it again. The engine now records the throw as
  the step's failure, so retries and compensation engage. Reporting `ERROR` yourself is still the
  contract — a throw carries no variables and no message of your choosing.

### Changed
- **A step with nothing to wait for no longer runs.** Eligibility asked whether every
  precondition was satisfied, and every precondition of none is satisfied — so "no preconditions"
  meant "start immediately". That is why a compensation step had to be anchored to some step it
  had no relationship with and guarded with `"preconditionExpression": "false"`: a fiction whose
  only job was to keep the dataflow away from it, that had to be written correctly every single
  time, and that turned into a live branch of the happy path when it was not. A compensation is
  declared on the step it undoes and started by the rollback pipeline; it needs no way in of its
  own, and now it may have none.

  Only a flow's entry points run with no preconditions: `START`, and a `WAIT_FOR_MESSAGE` that
  begins a flow rather than sitting inside one. The roots rule becomes a reachability rule —
  a step with no preconditions that is neither of those nor another step's `compensationStepId`
  is rejected at load, because nothing would ever start it. Definitions using the false-guarded
  anchor keep working unchanged, in the engine and in the Maven plugin's build-time validation;
  the anchor was never real flow and is still ignored by the topology warnings.

### Added
- **A step that ran as a compensation is drawn amber in the process diagram**, with an undo badge
  instead of the green tick and a `COMPENSATION` chip on its hover card. A compensation that
  succeeds is `COMPLETED` like any other step, so it was drawn in the same green as the work it
  had just undone: a fully rolled-back saga read as a successful one with a few extra boxes. The
  graph already knew which steps those were — it draws their rollback edges — so nothing new is
  sent to it.
- **`workflow.embedded.worker-threads`** (plus `worker-queue-capacity` and
  `worker-shutdown-grace-ms`): hands embedded tasks to a bounded pool instead of running them on
  the dispatching thread. Off by default, because turning it on changes what delivery means —
  `Sent` becomes handed-off rather than finished, as it already is in `kafka` mode, so a task lost
  to a crash is recovered by the step's `timeout` rather than by redelivery, and two tasks of one
  process can be in flight at once. A full pool rejects, and the rejection is classified retryable
  so the outbox holds the message and offers it again rather than dead-lettering a queue for being
  busy.

## [1.0-beta.019] - 2026-08-05

The first time the whole thing was deployed to a real cluster — the engine and the seven demo
services, on Kubernetes, against one database — and it did not come up. Four separate reasons, none
of which a test or a lint could have found, because each of them only exists once the three
applications are pointed at one database and at one another. Two of them had been shipping in
releases for weeks: the rule engine's gRPC server has not started since `1.0-beta.009`, and the
chart named an image tag that was never published.

Nothing here changes how the engine behaves. It is the difference between software that passes its
tests and software that starts.

### Fixed
- **The orchestrator and the rule engine could not start next to the forms engine.** They share a
  database, each ships its own migrations numbered from V1, and all three wrote to Flyway's default
  history table: whichever started first put its V1 there and the other two refused to start on a
  checksum mismatch. Forms and rules now keep their own history tables, and all three baseline at
  version 0 rather than 1 — `baseline-on-migrate` reads a non-empty schema as "my tables are
  already here", which in a shared database means somebody else got there first, so the app skipped
  the baseline that creates its own tables and then failed on the first migration that touched one.
- **The rule engine's gRPC server never started**, in every release since `1.0-beta.009`: the stubs
  were generated with protoc 4.35.1 while grpc-java and Spring Boot's dependency management resolve
  protobuf-java 4.32.0, and generated code refuses to load on a runtime older than itself. The
  failing bean took the whole application context down with it. protoc is now pinned to the runtime
  the hosts actually get.
- **The database ran out of connections.** PostgreSQL allows 100 by default and the chart's own
  defaults ask for 96 — three apps, two replicas each, sixteen connections apiece — so anything
  else sharing it, including the demo's own services, was refused with "sorry, too many clients
  already". The chart now sets `postgres.maxConnections` (200), and the demo services take small
  pools out of it.
- **The chart deployed images that do not exist**: `appVersion` was the placeholder `0.0.0`, so
  installing straight from the repository left every pod in `ImagePullBackOff`. It names a real
  release, and the comment claiming CI pinned it is gone — nothing did.

### Added
- **A chart for the demo** (`charts/eventconductor-demo/`): the seven services, deployed as
  ordinary applications against an existing EventConductor release. Runtime images for the five
  that lacked them, and the gateway's routes, which point at localhost in the image and so answer
  "connection refused" to everything until a deployment tells them the real addresses.

### Documentation
- The Helm section of the deployment guide describes the chart as it is: the rule engine it also
  deploys, the password it requires to install at all, the values that matter, and the demo chart.


## [1.0-beta.018] - 2026-08-04

### Added
- **A process is one trace, not one per hop.** OpenTelemetry over OTLP was already wired and the
  documentation said the context propagated across the engine's asynchronous boundaries. It did
  not, for the boundary that matters: that boundary is a database row, not a network call. An event
  is written to the outbox inside the transaction that produced it and published by a relay thread
  afterwards, so auto-instrumentation saw a write in one trace and, later, a Kafka send belonging
  to nothing — and the consumer started a fresh trace. The outbox row now carries the producing
  trace's W3C `traceparent` (`V17`, null when nothing is being traced) and the relay publishes as a
  continuation of it.
- **Engine spans**: `eventconductor.step-over`, `eventconductor.dispatch-step` and
  `eventconductor.correlate-message`, so a trace shows what the engine was doing rather than only
  the queries it made along the way.
- **Metrics over OTLP** (`OTLP_METRICS_ENABLED`), for deployments that already run a collector.
  Off by default, and it does not turn Prometheus scraping off.

All of it through a `WorkflowTracing` port with a no-op default, wired to Micrometer only when the
host brings a `Tracer` — the engine libraries still run with zero observability dependencies, and
a tracing failure is logged at debug rather than being allowed to fail a workflow.

### Documentation
- The observability reference describes the outbox propagation, the engine's spans and the OTLP
  metrics export; the AI reference gained the definition status, per-link preconditions,
  `restartProcess` and the tracing summary; the IDE guide documents the workflow status in the
  graph's Settings panel and that a new `.ec` is written as YAML.

## [1.0-beta.017] - 2026-08-04

Two questions a definition could not answer before: which route into a step a condition belongs to,
and whether the workflow is meant to be running at all.

### Added
- **A precondition carries its own condition.** `preconditions: [{stepId, expression}]` puts a
  guard on the link rather than on the step, so a step reached from two places can require
  something different of each. A link whose condition is false is not satisfied, so the step
  **waits** — the literal reading of "wait for all of them", chosen over quietly dropping the
  branch, which would let a step run having waited for less than its author wrote. Documented
  including the cost: a condition that never becomes true is a process that never finishes, and one
  the stalled-step gauge cannot see. The step-level `preconditionExpression` is unchanged and still
  skips rather than holds.

### Changed
- **A definition says whether it may run, and the runtime cannot overrule it.** `status: ACTIVE |
  DISABLED | ARCHIVED` in the `.ec` is a floor: an operator can take a workflow out of service, but
  cannot put one into service that its own definition closes — which is what lets a definition live
  in a repository without being live. It replaces the `disabled`/`archived` booleans, which between
  them said what one word says; both are still read, in files and in the database.

### Fixed
- **Process creation ignored a disabled workflow.** Only the cron scheduler checked, so anything
  creating a process directly — the UI, an upstream event, an MCP call — walked straight past a
  workflow that had been taken out of service.
- **A git import silently put back into service anything an operator had disabled**, because the
  file and the runtime wrote the same field and whoever wrote last won. They are stored apart now:
  an import replaces the declaration and leaves the runtime decision alone.
- **The graph editor wrote a field the engine could not read.** Its Status dropdown produced
  `status: DRAFT|ACTIVE|DISABLED|ARCHIVED`, which the schema does not define and the importer's
  parser rejects — so such a file did not merely fail to disable anything, it stopped importing
  altogether. The editor now writes the real status, and the importer adopts the older spellings
  rather than choking on them.

### Plugins — IntelliJ 0.1.2, VS Code 0.1.1
- **Delete (or Backspace) removes the selected node or connection**, and deleting a node clears
  every reference to it — preconditions in either spelling, and the compensation pointer that used
  to be left dangling. Connections are selectable at all for the first time.
- **The animation follows the graph you are editing.** Its paths were derived only when the host
  pushed a value in, so an edit made in the editor left it walking the graph as it was before:
  new nodes on no path, deleted ones still on theirs. Selecting a node no longer restarts a paused
  simulation, and a node with nothing wired to it is not animated as a path of its own.
- Readable arrowheads, no expand button in an editor pane that is already the whole surface, a
  Settings panel whose rows line up with their labels, and a new `.ec` written as YAML rather than
  JSON.

### Migration
- `V15` and `V16` add the definition status columns; both are idempotent and run over a `ddl-auto`
  schema. No action beyond the `flyway repair` that 1.0-beta.016 already called for.

## [1.0-beta.016] - 2026-08-04

Tagged without release notes at the time; written down here after the fact.

### Added
- **Definition version history owned by the engine**, with per-version process statistics (#145),
  and a per-repository git subdirectory plus a stopped/waiting heatmap in the definition viewer
  (#144).
- **Run a stopped process again, two ways.** *Retry from failure* picks it up where it stopped;
  *restart from the beginning* re-runs every step, including the ones that succeeded, with the
  variables the process was created with. Both from the process list, the process detail and MCP,
  and both now accept a CANCELLED process and not only a failed one.

### Fixed
- **The engine's own workers were dropping replies.** The forms engine answering a USER_TASK and
  the rule runtime answering a RULE step still called `streamBridge.send` and discarded the
  boolean — the line that left 3 356 processes stuck. Both now reply through `WorkerReply`, and
  the synchronous-producer default that makes a refusal detectable moved to `shared`, so it
  reaches every module that can answer the engine rather than only those that embed it. The demo
  workers too.
- **`DB_POOL_SIZE` and `DB_CONNECTION_TIMEOUT` did nothing.** Every shipped configuration put the
  Hikari settings under `spring.hikari.*`, a prefix Boot binds nothing from, so every deployment
  ran HikariCP's default of 10 connections whatever the environment said. Moving them inside
  `spring.datasource:` makes the documented values real — **the effective pool changes on deploy**.
- **The stalled-step gauge counted people.** `eventconductor.steps.stalled` counted every live step
  with no deadline, which is what a human task is by design, so any deployment with USER_TASK steps
  reported permanent stalled work and warned about it every minute. Only ACTION and RULE steps —
  the ones a worker owes an answer for — are counted now.
- **The documented upgrade path did not run.** Adopting Flyway over a schema `ddl-auto` had already
  built failed at `V11`, which added columns the entity already declares and dropped columns
  Hibernate never created; the application did not start. Both shapes migrate now, and
  `DdlAutoToFlywayUpgradeTest` runs the whole chain over a Hibernate-built schema.

  :warning: **Action required on databases where `V11` already ran successfully.** Editing an
  applied migration changes its checksum, and Flyway validates checksums at startup: those
  databases need a one-off `flyway repair` before the application will start. Databases that never
  got past `V11` — the broken path this fixes — need nothing.
- Path focus (click and alt+click) works again in the process view of the workflow graph.

## [1.0-beta.015] - 2026-08-03

### Fixed
- **The transactional outbox was not transactional: 71 messages in 642 912 were marked `Sent` and
  never reached the broker.** The relay delivers before marking the row, which is the right order
  and bought nothing, because the send was asynchronous — `StreamBridge.send` returns `true` the
  moment the record is buffered, so a broker that was down still produced a row marked `Sent` and
  a process that never moved again. Producer sends are now synchronous by default, contributed by
  the engine itself (`SynchronousProducerDefaults`) rather than left to each application's YAML,
  and a refused send throws so the row stays `Pending`. Measured cost: **0.6 ms per transition and
  4.4% of peak throughput**. Set `spring.cloud.stream.kafka.default.producer.sync=false` to opt
  out, deliberately.

- **Workers were dropping replies, in the pattern this project teaches.** Every worker here called
  `streamBridge.send(...)` and discarded the `false` it returns on refusal, so a reply the broker
  would not take vanished, the consumer committed the offset anyway, and the task was never
  reported as done. During a 90-second broker outage that lost **3 352 replies and left 3 356
  processes permanently stuck, with no error logged anywhere**. New `WorkerReply` retries and then
  throws, so Kafka redelivers the task — worker handlers must be idempotent, as at-least-once
  delivery always required.

- **A step with no timeout was invisible, not merely un-timed-out.** The deadline scan is an index
  range over the deadline column, so a step without one was never looked at again: if its dispatch
  or its reply was lost, the process stopped forever and nothing reported it. Two changes — the
  `eventconductor.steps.stalled` gauge counts live steps with no deadline that have waited past
  `workflow.stalled-step-after-ms`, and `workflow.default-step-timeout-ms` gives ACTION and RULE
  steps a fallback deadline that hands them to the existing retry path. Off by default; never
  applied to USER_TASK, PROCESS or WAIT_FOR_MESSAGE, whose waiting is unbounded by design.

- **A rollback the database made impossible was treated as a defective event.** Stopping
  PostgreSQL mid-transaction produced `JpaSystemException: Unable to rollback against JDBC
  Connection`, which the classifier did not recognise as retryable, and two process creations were
  dead-lettered for it. Connection-level `SQLException`s are now matched on SQLState — class `08`,
  class `53`, PostgreSQL `57P01`–`57P03` — as well as by type. A constraint violation stays
  non-retryable: retrying that forever is a poison pill.

- **`forms` and `rules` still shipped with Flyway off.** Only the orchestrator had been fixed, so
  both ran the schema `ddl-auto` builds, which has no indexes. Both now default `FLYWAY_ENABLED`
  to `true`. `DB_POOL_SIZE` defaults to 16 across the three apps.

- **One consumer thread per pod meant a deployment could never use more partitions than replicas.**
  `KAFKA_CONCURRENCY` now defaults to 3. Measured on a three-pod cluster with six partitions: four
  consumed, two unread, and no pod anywhere near CPU-bound.

- **PostgreSQL and Redpanda declared no resource requests**, which makes them BestEffort and the
  first pods the kubelet evicts. Scaling the orchestrator from 3 to 6 replicas evicted the broker
  and stopped the engine completely. Both now have requests and limits in the chart.

- **Redpanda's shard count is pinned (`--smp`).** It is written into the data directory as an
  invariant, so a broker rescheduled onto a smaller node refuses to start — permanently — with
  "Decreasing redpanda core count is not allowed". On any cluster with a node autoprovisioner that
  turns a routine reschedule into an outage.

- **Two migrations shared version 11**, which makes Flyway refuse to start and the app die at boot
  with no schema. Both branches had merged green because nothing on the build path reads these
  filenames. A test does now.

### Added
- **A reliability harness** (`modules/workflow-benchmark/k8s/reliability`): a soak driver, seven
  chaos scenarios — pod kill, whole-tier kill, rolling redeploy, broker outage, database outage,
  node drain, and replacing the workflow definition mid-flight — and the invariants that decide
  whether anything was lost. The verdict is computed from the engine's own tables against a count
  the driver writes to the database, because the harness is inside the blast radius.

  Results after the fixes: **zero stuck processes** (3 356 before), a full drain in 144 s (it never
  drained before), zero duplicate step executions across 230 417 dispatches including 44 genuine
  redeliveries, and the definition swap producing exactly two shapes and no hybrids. See the new
  [Reliability guide](https://eventconductor.io/guides/reliability/).

### Fixed
- **Flyway runs by default.** `FLYWAY_ENABLED` defaulted to `false` while `DDL_AUTO` defaulted to
  `update`, so the out-of-the-box standalone app built its schema with Hibernate and never ran the
  migrations — and the migrations are the only place the engine's indexes come from, since
  `ddl-auto=update` emits no index DDL. The default deployment therefore ran every deadline scan,
  outbox claim and message correlation as a sequential scan.

  Enabling it is safe on a schema `ddl-auto` already created: it baselines at V1 and every later
  migration is written with `IF NOT EXISTS`. **Existing installs that override
  `flywayEnabled: false` should turn it back on** — the Helm chart already defaults it to true, so
  this only bites deployments that overrode it or run the app without the chart. Turn Flyway on
  first, confirm the indexes appear, and only then move `ddlAuto` to `validate`.

- **The demo services had the shared-consumer-group defect too.** `content-service` and
  `users-service` used one group for their `upstream` and `outbox` bindings, same as the
  orchestrator did.

- **A consumer group per binding. Sharing one left Kafka partitions with no consumer at all.**
  The orchestrator's two bindings — `upstream` and `outbox` — used the same group, so its members
  had different topic subscriptions. The default range assignor handles that badly: observed on a
  cluster, 12 members held 7 of 12 partitions between them, and the 5 unassigned ones were simply
  never read. Every process whose next event landed there stopped, and the pods sat near idle
  while work piled up.

  It was never visible locally because the suite is short and a rebalance eventually reshuffles
  the gap onto a consumer. It was costing throughput the whole time: DIST-05 goes from ~60 to
  **78–91 PI/s** with nothing changed but the group names.

  Fixed in the standalone app, the distributed harness and the benchmark. Anyone running their own
  orchestrator wiring should check the same thing: a consumer group's members must subscribe to
  the same topics.

- **Indexes are declared on the entities, not only in the migrations.** They only ever existed in
  Flyway, so a schema built by `ddl-auto` had primary keys and nothing else, and every deadline
  scan, outbox claim and correlation lookup became a sequential scan. On a cluster with ~9k live
  step rows that pinned PostgreSQL at 750m of CPU.

  **`ddl-auto=update` still will not create them** — Hibernate's update path emits no index DDL,
  which is worth knowing before relying on it. `create` and `create-drop` do, so the test
  harnesses now exercise the same plan as production. For a real deployment the migrations remain
  the answer, and `spring.jpa.hibernate.ddl-auto=update` is not a substitute for running them.

### Changed
- **The relay is woken by the write instead of finding out on its next poll.** A pod that commits
  an outbox row raises a signal its own relay is waiting on; the poll interval stays as the
  fallback for rows written by other pods, which a pod has no way to hear about directly.

  The poll interval was never a scheduling preference — it was latency added to every step, and a
  transition crosses the relay twice. Measured on the benchmark harness at 40 instances/s, p50 per
  transition:

  | `workflow.outbox-poll-interval-ms` | before | after |
  |---|---|---|
  | 500 (the shipped default) | 508,8 ms | **10,1 ms** |
  | 20 | 28,6 ms | **10,7 ms** |

  Fifty times at the default, and still nearly three times better than the most aggressive polling
  worth running — which also stops mattering, since the interval no longer sets the latency.

  The signal fires **after the commit**, not at the write: raised inside the transaction it would
  wake the relay to look for a row not yet visible, which finds nothing, goes back to waiting, and
  spends the wakeup — leaving exactly the latency it exists to remove, with an extra query on top.
  It carries one permit rather than a count, because the relay drains until empty and only needs
  to know that something arrived.

### Fixed
- **An event that lost an optimistic race is now redelivered instead of disappearing.** The
  rejection was caught, counted, logged as "the event will be redelivered" — and then the handler
  returned normally, the consumer committed its offset, and the event was gone. Nothing redelivered
  it. The log line was simply wrong. A rejection now propagates as
  `ConcurrentProcessAccessException` all the way out of the consumer, which is what actually stops
  the offset from advancing over work that never happened. It is deliberately the one failure that
  is not swallowed: the event is not defective, it just lost a race, so retrying is the right
  answer rather than a log line.

- **An event the engine cannot process is parked instead of vanishing into a log line.** It used
  to be caught inside the event use cases, logged once, and forgotten: never retried, never
  visible, and invisible to anyone not reading that log. Those catches are gone. A failure now
  reaches whoever delivered the event, which is the only place that can decide what to do with it,
  because the answer depends on how it arrived.

  **The policy is retry forever, or park at once — never "retry N times, then give up".** N
  attempts is a guess about how long an outage lasts, and getting the guess wrong drops events
  that would have worked. `EventFailures` decides: only failures known to be about the
  environment — database unreachable, lock not obtained, a lost race — are retryable, and the
  classification is deliberately narrow because being wrong towards parking is the safer
  direction. A parked event can be replayed once someone understands it; an event retried for
  ever is a loop nobody reads.

  Where it parks depends on the topology, because the two have different queues. In `kafka` mode
  it goes to a **dead-letter topic** (`deadLetter` binding), payload untouched so replaying is
  just republishing it on the destination its `x-dead-letter-source` header names, with the
  failure in headers and the process still as the key. In `embedded` mode the outbox table *is*
  the queue, so the resting place is its existing `Error` status — visible in the table, replayed
  by setting the row back to `Pending`. That path also fixes a quieter bug: a failed message was
  left `Pending`, so an event that could never succeed was retried every cycle for ever.

  New counter `eventconductor.events.dead.lettered` — the one that should make somebody look, since
  a retry is the engine coping and a dead letter is the engine giving up. Specs `DIST-12` and
  `E2E-DLQ-01`.

### Changed
- **A Kafka poll batch is committed as one transaction per process, not one per event.** A busy
  batch carries several events for the same process, and collapsing those into a single commit is
  most of the write cost. Measured on DIST-05, three runs: **56,8 / 60,3 / 56,8 PI/s against
  50,8 / 51,0 / 53,7** before — about 12% on wall clock and 20% on the engine-side window.
  Consumers switch to batch mode (`batch-mode: true` on the two orchestrator bindings; turning it
  off falls back to one transaction per event).

  **Per process, not per batch — and that distinction is the whole design.** One transaction for
  the entire batch is the obvious shape and it is a trap: an event that fails inside its own
  participating transaction marks the shared one rollback-only *even when the failure is caught*,
  so every other event in the batch loses the commit it believed it had. The failure that makes
  this concrete is an optimistic conflict, which is precisely what a consumer-group rebalance
  produces — the moment the engine is least settled would be the moment it threw away the most
  work. Measured both ways: the two perform the same, so the batch-wide version buys nothing for
  its blast radius. `BatchTransactionScopeTest` pins the trap; `DIST-12` covers the behaviour.

  Events of a process keep their arrival order, and an event belonging to no process gets a
  transaction of its own so it neither drags others down nor is dragged down by them.
- **No per-process lock in `kafka` mode: exclusivity is inherited from the partition.** Events are
  keyed by process and a consumer group gives each partition to exactly one consumer, so a process
  already has a single writer. `PartitionOwnedProcessLockService` therefore only opens the
  transaction the work commits in; the optimistic version is what guards the rebalance window.

  Measured on DIST-05, three runs each: **44,0 / 46,1 / 50,7 PI/s with the row lock against
  50,8 / 51,0 / 53,7 without** — about 10% on the mean, and a visibly tighter spread, since the
  lock was the part that could wait. The lowest run without it beat the highest run with it.

  **`embedded` mode keeps the row lock**, deliberately: nothing partitions processes across pods
  there, so two of them can still reach the same process, and a version guards a *row* rather than
  a *decision* — two step-overs that read the same state and then write different rows collide on
  no version and would dispatch a step twice.

  That same reasoning left one hole in kafka mode, and it is closed rather than documented away:
  a worker on an older shared module reports back **unkeyed**, so its event can land on a pod that
  does not own the process. `UnkeyedEventRouter` now sends such an event back out with the process
  it belongs to instead of handling it where it landed — one indexed lookup and one extra hop,
  paid only by workers that do not echo the process, and inert outside kafka mode.
- **Operator actions travel as events, so they run on the pod that owns the process.** Pausing,
  resuming, cancelling and retrying (a whole process or one step) used to execute wherever the UI
  click or the MCP call landed — which, under partition ownership, is not the pod that owns the
  process. They are now published keyed by the process: `PauseProcessRequested`,
  `ResumeProcessRequested`, `RetryProcessRequested`, `RetryStepExecutionRequested`, and the
  long-declared but never-used `ProcessCancellationRequested`, which gains a `processId` because a
  process with no business key cannot be addressed by one. An operator action goes through the
  same single writer as everything else instead of being the one path that needs a lock to be safe.

  **In embedded mode nothing changes:** the upstream publisher dispatches in-process, so a UI
  action is still carried out synchronously before the call returns. It is only in `kafka` mode
  that these become routed — and there the MCP tools now answer "requested… query the process to
  see the outcome" rather than claiming the work is done.

  There are no REST endpoints for these operations; the entry points are the UI and MCP.
  Specs `E2E-OPS-01..05`.
- **Optimistic locking on `Process` and `StepExecution`.** Both aggregates now carry a `version`,
  checked on every write (migration `V11`, existing rows backfilled to 0 — a null version is how
  Spring Data recognises a row it has never persisted, so leaving them null would turn updates
  into failed inserts).

  This is the fence for the one hole in ownership. Keying events by process gives each process to
  a single pod, but a consumer group guarantees which consumer is *assigned* a partition, not
  which is still *in flight*: during a rebalance the outgoing pod can be finishing a record the
  incoming one has just been handed. A stale writer's update now matches no row at its version
  and is rejected, rather than quietly overwriting the new owner's work.

  It costs nothing when there is no conflict — no waiting, no lock held, no connection parked —
  which is what makes it able to replace the pessimistic lock rather than sit beside it.

  **Rejections are counted, not just logged**: `eventconductor.process.concurrent.writes.rejected`.
  That metric is the point of this change as much as the safety is. Outside a rebalance it must be
  flat at zero; anything else means something is reaching a process from outside its partition —
  exactly what has to be true before the pessimistic lock can be removed. Specs `E2E-LOCK-01..03`.
- **Events are keyed by process, so a process belongs to one pod.** Every event that concerns a
  process now carries it as the Kafka message key (`DomainEvent.partitionKey()`), so all of a
  process's events hash to the same partition — and a consumer group gives each partition to
  exactly one consumer. Per-process serialization stops being something the engine arranges after
  the fact and becomes a property of how events are addressed.

  **This also fixes ordering, which is a correctness matter rather than a performance one.** On an
  unkeyed topic two events of the same process land on different partitions and are handled
  concurrently by different pods, in whatever order they arrive; the per-process lock serialized
  access but never ordered it. What made that survivable was the terminal-status guards and the
  re-reads inside the lock. Keyed, the order is the order they were produced in.

  Two events that mutate process state carried only a task id and now carry the process too:
  `TaskStatusChanged` (a worker's reply, echoed from the `TaskExecutionRequested` it received) and
  `StepExecutionStatusChanged`. Both keep their previous constructor, which leaves the key null —
  so a third-party worker built against an older shared module still compiles, still deserializes,
  and simply falls back to the unrouted behaviour it has today. Events that write only their own
  independent row (`TaskLogEmitted`, `TaskResourceCreated`) stay unkeyed on purpose: pinning them
  to a partition would cost balance and buy nothing.

  Nothing is removed yet — the per-process lock stays as the safety net, since ownership is only
  a Kafka *assignment* guarantee and a rebalance can still put two pods on one process briefly.
  New spec `DIST-11` verifies the keys by reading the topics, not by trusting that `send` set one.
- **BREAKING (SPI): per-process exclusion is a row lock, not an advisory lock.**
  `ProcessLockService` loses `tryLock`/`unlock` and gains a single
  `runExclusively(processId, action)`. In JPA mode the action now runs in a transaction that opens
  by taking `SELECT … FOR UPDATE` on the process row, and the commit releases it. Anyone who
  implemented this port has to follow; nothing else about the engine's concurrency semantics
  changes.

  The reason is not elegance. An advisory lock is session-scoped, so acquiring it took a
  connection out of the pool and **held it for the whole critical section**, while the work inside
  needed a second one. Two connections per in-flight process made the pool size — not the database
  — the ceiling on concurrency, and past that point the failure was a **wedge, not a slowdown**:
  lock holders waiting for a connection to do the work they held the lock for. New spec `DIST-10`
  pins this down: 40 concurrent processes complete through a 3-connection pool, and restoring the
  two-connection shape exhausts it and stalls the processes outright.

  What else falls out: the stale-lock watchdog is gone, and with it the chance of force-releasing
  exclusivity from an operation still running; `ProcessLocks.lockWithRetry` is gone, because
  waiting is now the database's row-lock queue rather than a sleep-and-retry loop that reopened a
  connection on every attempt; exclusivity is reentrant within a transaction; and the lock key is
  the process id itself rather than a 64-bit fold of its UUID. Waiting is bounded by
  `workflow.process-lock-timeout-seconds` (default 10), applied as a statement timeout, which is
  portable in a way that per-vendor lock-timeout settings are not.

  `UpdateStepExecutionUseCase` drops its own `TransactionTemplate`: exclusivity and the
  transaction are now the same scope, so the inner one only joined the outer.
- **Every pod relays the outbox now — it is no longer a leader-elected singleton.** In `kafka`
  mode one pod drained the whole outbox while the others idled, and since every state transition
  passes through the relay, that put a ceiling on the distributed topology that adding pods could
  not lift. Relays now claim bounded batches with `FOR UPDATE SKIP LOCKED`, so N orchestrators
  drain N disjoint slices with no leader between them. New spec `DIST-09` proves the claims are
  disjoint, non-blocking and complete, against real PostgreSQL.

  The at-least-once contract is unchanged: a batch is claimed, published and marked `Sent` in one
  transaction, publishing still happens *before* the rows are marked, and a crash anywhere in
  between rolls back and releases the locks so another pod redelivers.

  Three related fixes in the same path: the relay **no longer loads the entire pending outbox**
  on every cycle (bounded batch, new `workflow.outbox.batch-size`, default 100) but keeps draining
  until it is empty so a backlog is not paced by the poll interval; per-message logging drops from
  `INFO` to `DEBUG`; and `workflow.outbox-poll-interval-ms` **defaults to 500 ms instead of
  5000** — it used to add up to five seconds of idle latency to every step hop in `kafka` mode,
  and the published DIST-05 throughput baseline was only ever reachable by overriding it.
  Migration `V10` indexes `(status, timestamp)`, which the claim's ordering needs.

  The **embedded** relay deliberately keeps its leader lock: its "delivery" is the engine running
  a step synchronously, taking the process lock and its own connections, and holding row locks and
  a transaction across all of that would mean long transactions and a plausible deadlock against
  the very work being dispatched. It does take the bounded fetch and the log-level fix.

  The relay's old advisory lock survives as a **shared** gate that every relay holds while
  draining: shared holders do not block each other, so this costs nothing, but a single exclusive
  holder still freezes every relay at once — the deterministic crash window DIST-02 and DIST-08
  are built on.
- **An arriving message finds its waiting steps by index.** A live `WAIT_FOR_MESSAGE` step now
  stores the subscription it represents — `awaiting_message_name` and `awaiting_correlation_key`
  — so correlating a `MessageReceived` is a lookup on those two columns instead of a walk over
  every step waiting anywhere in the engine, loading each one's process and evaluating its JEXL
  expression. Indexing the message name alone would not have helped: the case that hurts is many
  processes parked on the *same* message, where the key is the only selective part.

  **The correlation contract is unchanged**, and that is what most of the work went into. The key
  derives from process variables, and those move while a step waits — a parallel branch can write
  the very variable the expression reads. Evaluating on arrival made that free; storing it does
  not, so both paths that update process variables now rearm the subscriptions of that one
  process (`MessageSubscriptionService`), writing only the keys that actually moved. A message
  still correlates against the process as it is *now*. Spec `E2E-MSG-06` covers it. Fail-closed
  survives too: an expression that will not evaluate stores a null key, and null matches nothing.
  `CompleteMessageStepHandler` still re-checks the correlation against the live process under the
  process lock — the query is the filter, that check remains the decision.

  Steps already waiting when this version is deployed are armed at the next boot by
  `InFlightStepRearmRunner` (which also covers the deadline below). Migration `V9` adds the
  columns and `idx_step_exec_awaiting_message`.
- **The scheduler no longer walks every live step to find the due ones.** Each step execution now
  carries a **materialised deadline** (`deadline_at`) — a `TIMER`'s due moment or a step's timeout
  — armed when the step starts, from the `startedAt`, variables and step JSON that are frozen at
  that instant. The scheduler asks for `deadline_at <= now` over a new index instead of loading
  every PENDING/RUNNING step and re-evaluating each one on every tick. The cost of a tick now
  tracks *what is due* — normally nothing — rather than *what is waiting*, which is what the
  engine's own use case is made of: a check-in reminder is a `TIMER` sitting PENDING for weeks,
  and it used to be re-examined every ten seconds for all of them.

  The deadline is derived state, so it is recomputed by every path that moves the clock;
  `withDeadlineAt` is suppressed on the aggregate so it cannot be set on its own, and pause/resume
  (which shifts `startedAt` by the pause duration) moves both together. Steps already in flight
  when this version is deployed are armed at the next boot by `InFlightStepRearmRunner`, which
  recomputes them from the state they already carry — one query at startup, idempotent, a no-op
  from then on. Migration `V8` adds the column and `idx_step_exec_deadline`.

  No behaviour change, with one millisecond-scale exception: a step timeout falling exactly on the
  tick now fires on that tick instead of the next, matching what `TIMER` already did.

### Fixed
- **Timer and timeout checks no longer load every live step in the system.** `CheckTimerUseCase`
  and `CheckTimeoutUseCase` listed *all* PENDING/RUNNING step executions and filtered them by
  process in memory. Because the scheduler scan fans out one check event per due process, a
  single scan tick that found N due processes triggered N full loads of the live-step table, on
  top of its own. Both now query only the process they were commanded for, through a new
  `StepExecutionRepository.findPendingOrRunningByProcessId(processId)`. The cost matters most on
  the workloads the engine is built for — long waits, where tens of thousands of `TIMER` steps
  sit PENDING for weeks. New composite index `idx_step_exec_process_status` on
  `step_execution_entity (process_id, status)` replaces the process-only index it subsumes
  (migration `V7`). No behaviour change.

## [1.0-beta.014] - 2026-08-01

### Added
- **IDE plugins for VS Code and IntelliJ IDEA.** New editor plugins (under `plugins/`) open a
  workflow definition as an **interactive graph** or as plain **YAML/JSON**, both views editing the
  same file, with schema validation, and they embed the exact graph web component the app renders.
  VS Code ships a custom editor (graph by default, *Show YAML/JSON side-by-side*); IntelliJ
  (2024.2+) ships a split graph/text editor via JCEF. Published to the VS Code and JetBrains
  marketplaces. See [IDE Plugins](/guides/ide-plugins/).
- **`.ec` — a first-class workflow-definition extension.** An `.ec` file holds a definition as
  **JSON or YAML** (detected from the content and preserved on save). The git import and the
  classpath importer now read `.ec` alongside `.json` / `.yaml` / `.yml`.
- **JOIN gains an AND/XOR type.** A `JOIN` now carries a `joinType`: **`AND`** (default) is a
  synchronizing join that waits for all incoming branches; **`XOR`** is an exclusive join that
  proceeds as soon as any one completes. Null/absent = `AND`, so existing definitions are
  unaffected. The precondition check honours it (all-match for AND, any-match for XOR).
- **Live process state on the in-app graphs (read-only monitoring).** The workflow-definition view
  badges each node with how many live process instances currently sit on it; the process view gains
  a *Diagram* tab that shows the graph with the active step highlighted and the parts not yet
  reached dimmed. Driven by a new `overlay` property on the graph component.
- **Workflow graph editor — major UX pass.** Zoom/pan with fit-to-view and a minimap; dark-mode
  support outside Lumo (for the IDE webviews); BPMN-style event/gateway shapes with an
  exclusive-gateway (`×`) glyph for XOR joins; a token-flow simulation that dwells on long-running
  steps and, on an AND-join, lights up all its incoming branches to show it synchronising;
  node-avoiding, non-overlapping, shape-fitting edge routing; and drawing precondition lines by
  Shift+drag. The same built bundle is reused by both IDE plugins.
- **Whole-process saga rollback in reverse execution order.** When any step fails or times out
  after exhausting its retries, the engine now compensates **every executed rollbackable step**
  (completed steps plus the one that just failed) — not only the failed step's own
  compensation — running them **sequentially, in reverse execution order**: the latest-executed
  step is undone first, and each compensation starts only once the previous one completes. The
  next compensation is derived purely from persisted step-execution state (new
  `CompensationService`), so it is idempotent under redelivery and across restarts. A single
  failed rollbackable step is just the degenerate case of this cascade. See the new
  `COMPENSATED` terminal state under Changed.
- **Git reload webhook: multiple providers, targeted reload and pruning.** The
  `POST /{engine}/webhooks/{provider}` endpoint (workflow, forms and rule engines) now accepts
  `github`, `gitlab`, `bitbucket` and `generic` (`/github` keeps its behaviour), each
  authenticated with the configured `webhook-secret` — GitHub/Bitbucket HMAC-SHA256
  (`X-Hub-Signature-256`/`X-Hub-Signature`), GitLab (`X-Gitlab-Token`) and generic
  (`X-Webhook-Token`) tokens; a blank secret skips verification. The push payload is parsed to
  reload **only the repository and branch that changed** (an unmatched push is acknowledged and
  ignored; an unparseable payload falls back to reloading everything). Shared, reusable helpers
  live in a new `io.mateu.workflow.webhook` package. See pruning under Changed.
- **Workflow graph editor: multiple incoming preconditions per step.** The graph now renders
  and edits several `preconditionStepIds` into a single step (one edge per precondition, a
  multi-select editor) — the engine already honoured them; only the editor had modelled a
  single incoming edge.
- **Workflow graph editor: restyled, more expressive SVG.** The graph is redrawn in a richer
  visual language on the same Lit + ELK stack: per-type node cards with a corner glyph and an
  uppercase caption (`→ topic`, `👤 form`, `ƒ rule`, `⨝ JOIN`, `✉→ message`, …), dashed
  `FORK`/`JOIN` gateways, orthogonal rounded edges with arrowheads, a themeable palette
  (`--ec-*` custom properties, with a Lumo dark-mode mapping), and a step's
  `preconditionExpression` guard shown as a chip on its incoming edge.

### Changed
- **At most one `START` per workflow.** More than one `START` step is now rejected at validation.
  Multiple `END` steps remain valid — a flow may finish through several distinct outcomes.
- **Gateway-model guidance (warnings, not errors).** The validator now logs non-fatal warnings
  nudging a multi-input step toward a `JOIN` (with explicit AND/XOR semantics) and a multi-output
  step toward a `FORK`. Compensation anchors are excluded and conditional (guarded) splits stay
  allowed; it never blocks a definition.
- **BREAKING: a fully compensated process ends `COMPENSATED`, not `ERROR`.** A failed process
  that runs its saga rollback to completion now reaches the new terminal
  `ProcessStatus.COMPENSATED` instead of remaining `ERROR`; if a compensation itself fails
  after its retries, the chain halts and the process stays `ERROR`. `COMPENSATED` is a sticky
  terminal failure state (like `ERROR`), distinguished by whether the side effects were undone.
  Consumers, queries and dashboards that treat `ERROR` as the only failure terminal — and a
  parent `PROCESS` step, which now also errors on a `COMPENSATED` child — should account for
  the new state.
- **BREAKING: the git reload webhook reloads a subset and prunes removed definitions.**
  Previously every webhook call re-imported **all** configured repositories and only
  added/updated definitions. It now reloads only the repository and branch named in the push,
  and definitions that were removed from a repo are **pruned** — workflow definitions are
  archived (`ARCHIVED`), forms and rules are deleted (git-imported definitions only, never
  classpath or hand-authored ones; tracked per running instance). Only definitions with an
  explicit `id` are prune-tracked.

## [1.0-beta.013] - 2026-07-31

Cumulative since `1.0-beta.010` — releases `1.0-beta.011` and `1.0-beta.012` were cut
without changelog sections, so their contents are included below.

### Added
- **Pause/play for processes and workflow definitions.** New `ProcessStatus.PAUSED`:
  `PauseProcessUseCase` pauses a `PENDING`/`RUNNING` process and `ResumeProcessUseCase`
  puts it back to `RUNNING`. Pause freezes the frontier, not in-flight work — running
  workers finish and their reports are accepted (steps complete, variables merge), and
  messages still complete `WAIT_FOR_MESSAGE` steps — but successors do not start until
  resume. Clocks freeze too: timeout and TIMER scanning skip paused processes and, on
  resume, every non-terminal started step's `startedAt` is shifted forward by the pause
  duration (recorded in the new `Process.pausedAt`), so step timeouts and timer
  due-moments resume where they left off; blocking-error handling is deferred the same
  way, and cancelling from `PAUSED` works. At the definition level, a new runtime
  `paused` flag (orthogonal to the `DRAFT`/`ACTIVE`/... lifecycle; in the schema as
  nullable boolean, default `false`, so exports round-trip): `PauseWorkflowUseCase` sets
  it and pauses all the definition's `PENDING`/`RUNNING` processes, while new instances —
  cron occurrences included — are still accepted and created **born-`PAUSED`**;
  `ResumeWorkflowUseCase` clears it and resumes all its `PAUSED` processes. Surface:
  **Pause**/**Resume** toolbar actions on the process detail and on the definition detail
  (plus a **Paused** row), and four MCP tools — `pauseProcess`, `resumeProcess`,
  `pauseWorkflow`, `resumeWorkflow`. Flyway migration V6 in the orchestrator app
  (`process_entity.paused_at`, `workflow_definition_entity.paused`).
- **`START` step type — explicit workflow entry points.** A no-worker node that completes
  instantly at process creation, fanning the flow out to its successors. A `START` must have
  no preconditions, and declaring several gives the process concurrent entry branches. Every
  flow must now enter through a `START` or a `WAIT_FOR_MESSAGE` (see the roots rule under
  Changed).
- **`FORK` and `JOIN` are now implemented, on multi-preconditions.** Steps gain
  `preconditionStepIds` (array): ALL the listed steps must complete before the step starts;
  the singular `preconditionStepId` remains valid (the plural wins when both are set). `FORK`
  and `JOIN` are no-worker nodes that complete instantly — `FORK` is the explicit fan-out
  (all its successors start concurrently when it completes), and `JOIN`'s barrier is exactly
  its multiple preconditions (the converge point of parallel branches). Precondition-cycle
  detection is now a DFS over the multi-edge graph. The Maven plugin emits a build-time
  warning (never a failure) when a `JOIN` waits directly on a guarded step — if the guard is
  false the join never fires and the flow beyond it is cancelled.
- **`PROCESS` step type — child workflows are now implemented.** A `PROCESS` step (required
  `childWorkflowDefinitionId`, which must differ from the workflow's own id) starts a child
  process carrying ALL parent variables under the deterministic businessKey
  `parent:<stepExecutionId>` — idempotent, duplicate creation events are deduped. The parent
  step waits `PENDING`; when the child completes, the parent step completes and copies back
  only the child variables named in the new `outputVariables` step field (absent/empty =
  none); a child ending `ERROR` or `CANCELLED` puts the parent step in `ERROR` (normal
  retry/compensation pipeline), and `timeout` bounds the wait. `Process` gains
  `parentStepExecutionId` (Flyway migration V5 in the orchestrator app). Cancellation
  propagates parent→child: a parent `PROCESS` step ending `CANCELLED`, `ERROR` or `TIMEOUT`
  (retries exhausted) cancels a still-running child, cascading to grandchildren; while
  retries remain the child keeps running and a retried step re-attaches to it through the
  deterministic business key.
- **Graph and build-time validation support for the new model.** The workflow graph renders
  multi-precondition edges and `START`/`FORK`/`JOIN` nodes, and the Maven plugin's
  `SpecValidator` mirrors all the new invariants (roots rule, START-without-preconditions,
  plural precondition references, multi-edge DFS cycle detection, the PROCESS child id).
- **`SEND_MESSAGE` step type — fire-and-forget in-engine messaging.** The throw side of
  `WAIT_FOR_MESSAGE`: on start the engine evaluates the step's `correlationExpression` (JEXL,
  same context as preconditions), emits a `MessageReceived(messageName, correlationKey,
  variables)` through the outbox and completes the step immediately — no worker, no `ACTION`
  step needed for process-to-process signaling. The new optional `messageVariables` field
  (array of process-variable names) selects which variables the outgoing message carries;
  empty or absent means none — process state is never sent implicitly. Delivery is not
  acknowledged and a message matching no waiting process is discarded (not buffered). A
  missing `messageName`/`correlationExpression` or an unevaluable correlation key puts the
  step in `ERROR` (fail loud, normal retry/compensation pipeline) — deliberately not the
  silent fail-closed of precondition guards. The Maven plugin's `SpecValidator` now also
  checks `correlationExpression` JEXL syntax at build time.
- **`message-received` deliverable via the Kafka `upstream` topic.** `MessageReceived` is now a
  registered `DomainEvent` subtype (`"type": "message-received"`), so external producers can
  resume waiting `WAIT_FOR_MESSAGE` steps by publishing raw JSON to `upstream` — previously
  only REST (`POST /workflow/api/messages`), the MCP `sendMessage` tool or the Java API could.
- **Engine observability — metrics parity across all engines.** The Micrometer metrics pattern
  that already existed in the workflow engine (an `application/out` port with no-op defaults, a
  Micrometer implementation in `autoconfigure`, and an autoconfiguration guarded on a
  `MeterRegistry` bean) has been mirrored into the other engines. Metrics stay inert unless the
  host application provides a `MeterRegistry` (e.g. via Spring Boot Actuator), so libraries still
  run with zero observability dependencies.
  - **forms-engine** (`FormsMetrics`): `eventconductor.forms.task.created`,
    `eventconductor.forms.task.completed`, `eventconductor.forms.task.cancelled`,
    `eventconductor.forms.task.duration`, `eventconductor.forms.imported`.
  - **rule-engine** (`RuleCatalogMetrics`): `eventconductor.rule.catalog.saved`,
    `eventconductor.rule.catalog.deleted`, `eventconductor.rule.catalog.imported`,
    `eventconductor.rule.catalog.served`.
  - **rule-runtime** (`RuleRuntimeMetrics`): `eventconductor.rule.evaluation.count`,
    `eventconductor.rule.evaluation.duration`, `eventconductor.rule.evaluation.cache`. The
    runtime keeps working as a plain (non-Spring) library — the metrics port defaults to a no-op
    via overloaded constructors.
- **Distributed tracing (OpenTelemetry over OTLP).** The `orchestrator`, `forms` and `rule`
  standalone apps now ship `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` and
  expose `management.tracing` / `management.otlp.tracing` configuration. Tracing is **off by
  default** (`TRACING_SAMPLING=0.0`); set `TRACING_SAMPLING` and `OTLP_TRACING_ENDPOINT` to emit
  spans. Because it is enabled at the app layer, HTTP, Kafka (Spring Cloud Stream) and JDBC calls
  are auto-instrumented and trace context propagates across the engines' async boundaries without
  any engine-code changes — consistent with the metrics' "optional, host-activated" design.
- **Observability docs** — the docs site gains a dedicated Reference → Observability page
  (`doc/src/content/docs/reference/observability.md`) covering the metrics of every engine
  (workflow, forms, rule catalog, rule runtime) plus a Distributed tracing section (how to
  enable Prometheus scraping and OTLP tracing); the Configuration reference keeps a short
  pointer to it.
- **Boot without the database and resume when it appears.** `DbLockDialectFactory` was the only
  startup-time database access left: it now falls back to inferring the lock dialect from the
  JDBC url when the database is unreachable (the pollers already retry every tick), so an
  orchestrator configured for resilience (lazy pool, `ddl-auto: none`, explicit dialect) boots
  in seconds with PostgreSQL down, and a process parked mid-flight with pending outbox rows
  completes once the database returns. Proven by a new distributed chaos test (DIST-08) that
  pauses/resumes PostgreSQL with `docker pause`, like the Kafka chaos tests.
- **REST endpoint to deliver messages to `MESSAGE` steps.** `POST /workflow/api/messages`
  publishes a `MessageReceived` through the same upstream surface as Kafka, the embedded
  publisher and the `sendMessage` MCP tool, so webhooks and SaaS callbacks that cannot produce
  to Kafka can resume waiting `MESSAGE` steps. Fire-and-forget (`202 Accepted`), with an
  optional `X-Api-Key` guard configured via `workflow.message-api.api-key`.
- **Read-only workflow definition detail view with graph.** Selecting a definition in the CRUD
  now opens a dedicated `WorkflowDefinitionDetailView` (the "view" action) instead of the
  editor: name as the title, lifecycle status as a header badge, a compact property list and a
  read-only ELK graph side by side, with the steps as a full-width band below. The editor
  layouts were tidied (5-column definition grid; the Step form's Main / Precondition /
  Execution / Reliability sections sit side by side), a `PROCESS` step can no longer pick the
  workflow it belongs to as its child, and the graph component gains a read-only mode (toolbar
  and node-detail panel hidden, capped height with a full-screen expand button).
- **Lifecycle actions on the detail view + Export YAML.** The read-only detail view now carries
  the full lifecycle toolbar (*Promote to production*, *Create working copy*, *Disable*,
  *Enable*, *Reactivate*, *Archive*) with the same visibility rules as before, so an `ACTIVE`
  definition can again be disabled or copied from the UI. Any `DRAFT` is now promotable (a
  standalone draft is activated in place), and *Export YAML* downloads the definition in the
  exact shape the git/classpath importers read back.
- **Definition-level guardrails against runaway loops.** `WorkflowDefinition.checkInvariants()`
  now rejects precondition cycles (a step waits for its `preconditionStepId` to complete, so a
  cycle deadlocks; the error names the cycle), and the model and JSON schema gain per-step
  execution caps — `Step.maxSuccessfulExecutions` and
  `WorkflowDefinition.defaultMaxStepExecutions` (`0` = inherit/unbounded) — carried through
  working copies, imports and persistence, to be enforced when step re-execution lands.

### Changed
- **BREAKING: the execution model is now pure dataflow.** A step starts when it is `CREATED`,
  ALL its preconditions have `COMPLETED` and its `preconditionExpression` is truthy — and
  every eligible step starts **concurrently**. The old scheduling semantics are gone: array
  order no longer matters, an active step no longer serializes independent chains, and
  `parallel: true` no longer opts into concurrency — the flag is **deprecated and ignored**
  (it still deserializes, so persisted stepJson and old files keep loading, but it has no
  effect). Parallelism is expressed structurally: shared preconditions fan out (`FORK` makes
  it explicit), multiple preconditions form a barrier (`JOIN`).
- **BREAKING: roots rule — every flow must enter through a `START` or `WAIT_FOR_MESSAGE`.**
  A step with no preconditions must be one of those two types; definitions violating this are
  rejected at load (and by the Maven plugin at build time). Migration: add one `START` step
  and point your old first steps at it; anchor compensation steps to the step they compensate
  with `"preconditionExpression": "false"` (the compensation pipeline starts them directly,
  ignoring the guard).
- **BREAKING: `MESSAGE` step type renamed to `WAIT_FOR_MESSAGE`, and `correlationExpression`
  is now required on both message step types.** New or reimported definitions must use the
  new name and declare `correlationExpression` explicitly — the old defaults-to-`businessKey`
  behavior is gone; add `"correlationExpression": "businessKey"` to keep it. A legacy
  deserialization alias keeps old persisted stepJson and definition files loading (`MESSAGE`
  maps to `WAIT_FOR_MESSAGE`, and those legacy steps retain the businessKey fallback), so
  in-flight processes survive the upgrade. To support the idiom, `businessKey` is now seeded
  as a plain variable into every JEXL evaluation context (correlation **and** precondition
  expressions) — property access such as `process.businessKey` was never evaluable, because
  the JEXL engine deliberately runs with `RESTRICTED` permissions on untrusted expressions.
- **Upgraded Mateu to `3.0-alpha.271`.** `3.0-alpha.271` is a breaking release that removed the
  UI CRUD API (`CrudRepository`, `CrudAdapter`, `CrudEditorForm`, `CrudCreationForm`,
  `ListingBackend`, the `core.infra.declarative.Listing` base and `AutoNamedView`) and split
  `Searchable` into a marker interface plus a new `SearchableText`. The admin-UI layer of all
  engines was migrated:
  - `CrudRepository` → `CrudStore` (the method set is unchanged) across ~24 ports/pages/adapters.
  - Pages whose detail view differs from the row (`Processes` → `SimpleProcessViewModel`,
    `WorkflowDefinitions` → `WorkflowDefinitionDetailView`) and the read-only `Tasks` listing now
    extend `Crud<View, …>` directly instead of `AutoCrud`/`FilteredAutoCrud` (which pin
    `View = Row`), because `Navigable.view()` is now generically typed.
  - `rule-engine` was pinned to Mateu `3.0-alpha.222`; it now tracks `${mateu.version}` like the
    rest of the reactor.
  - In the demo services (booking, content, control-plane, shell, users) the `CrudAdapter` layer
    was dissolved into the crud itself: every crud now implements the whole lifecycle
    (`search(SearchRequest)`, `view`, `edit`, `creationForm`, `save`, `create`, `deleteAllById`)
    in a single `Crud`/`AutoCrud`/`FilteredAutoCrud` subclass, and the demo `*CrudAdapter`
    classes were deleted. The workflow engine keeps its adapters (`SimpleProcessCrudAdapter`,
    `StepExecutionsCrudAdapter` and the process child-crud adapters) as collaborators of the
    migrated pages.
  - Testbench UI apps declare `spring-boot-starter-webmvc` explicitly: the Mateu MVC annotation
    processor now generates SSE-capable controllers and the engines only carry the starter as
    `optional`.
  - Everything builds against the released `3.0-alpha.271` — the engine reactor and the
    standalone demo and testbench apps (which have their own poms outside the reactor) alike.
- **The Workflow Definitions admin page is now read-only** (list + rich detail view). Definitions
  are authored as YAML and loaded from the classpath, Git or the database, and were never created
  or edited from this page; the write actions are disabled rather than reimplemented on the new
  API.

### Removed
- Dead `ProcessCrudAdapter` (superseded by `SimpleProcessCrudAdapter`; only referenced by an
  unused import) was deleted as part of the Mateu 271 migration.

### Fixed
- **`END` no longer records co-eligible sibling steps as `COMPLETED` without running them.**
  When an `END` became eligible in the same transition as another executable step, that step
  was silently marked `COMPLETED` even though it never ran; it is now `CANCELLED` like every
  other in-flight step the `END` terminates.
- **`MessageReceived` was missing from `DomainEvent`'s `@JsonSubTypes`**, so a raw
  `message-received` event published on the Kafka `upstream` topic could not be deserialized.
  It is now registered as `message-received`.
- **Startup failure on databases with existing workflow definitions**: the new NOT NULL
  `default_max_step_executions` column could not be added by `ddl-auto=update` (Postgres rejects
  it without a default). `@ColumnDefault("0")` fixes Hibernate-managed schemas and a V4 Flyway
  migration covers Flyway-managed deployments; `0` = unbounded.
- **Process detail view**: explicit tab names keep *steps*, *log*, *errors* and *resources* as
  separate tabs (consecutive bare `@Tab` fields now merge into one tab in Mateu), and
  `CreateProcessForm` marks the form clean on create — creating *is* the save, so no
  "save before leaving?" prompt.
- **Workflow definition editor**: removed the backward-compatible `WorkflowDefinition`/`Step`
  constructors that made Mateu's `ReflectionInstanceFactory` build empty objects on save
  (`name=null`, `steps=[]`), and the precondition/compensation step lookups now exclude the step
  being edited, so a step can no longer be offered as its own precondition or compensation.

## [1.0-beta.010] - 2026-07-24

### Fixed
- **Kafka broker resilience.**
  - Corrected the default Kafka broker address to `localhost:9092` (was a `9192` typo), so
    a standalone app started from the IDE without `KAFKA_BROKERS` set connects to the local
    dev broker instead of rebootstrapping in an endless loop.
  - Standalone apps now **boot gracefully when the broker is unavailable at startup**. The
    Spring Cloud Stream binder's provisioning/admin timeouts are bounded and binding retry
    is enabled, so the context no longer blocks ~2 minutes (and loses its AdminClient) — it
    starts promptly and binds its consumers as soon as the broker is up.

### Added
- **Distributed chaos tests for Kafka broker outages** (`workflow-dist-e2e`): a process
  recovers when the broker disappears mid-flight and returns — driven by the transactional
  outbox (DIST-06) — and the orchestrator boots and processes normally when the broker is
  unavailable at startup and later returns (DIST-07).

## [1.0-beta.009] - 2026-07-23

### Added
- **Workflow definition lifecycle management** in the admin UI. Definitions move
  through `DRAFT` → `ACTIVE` → `DISABLED` → `ARCHIVED` with per-status toolbar
  actions: *Promote to production* (working copies only), *Create working copy*,
  *Disable*/*Enable*, *Archive* and *Reactivate*. An `ACTIVE` definition is
  read-only (edited through a working copy) and must be disabled before it can be
  archived; *Reactivate* returns an archived definition to `DRAFT`. New
  definitions are created as `DRAFT` and the status is never editable. Documented
  with a state diagram in `/guides/workflow-definitions`.
- EventConductor now **owns the workflow graph component** (moved out of mateu),
  served as a self-contained web component from the engine jar.

### Changed
- **Migrated to Spring Boot 4** (from 3.5): starter/autoconfig relocations,
  Jackson 3, Spring Cloud 2025.1, Spring AI 2.0 and networknt
  json-schema-validator 3.0.
- Adopted **mateu 3.0-alpha.263** (new `CrudStore` API). This brings the UI
  behaviour the lifecycle work relies on: the top navigation renders as a Vaadin
  menu-bar, an intermediate menu route (e.g. `/workflow`) shows a section index
  instead of "Not found", and the built-in *Edit* action is hidden on `ACTIVE`
  definitions.
- Coordinated **gRPC 1.83.0 + protobuf 4.35.1** bump; commons-jexl3 3.7.0; and
  Spring Cloud dependency updates.

## [1.0-beta.008] - 2026-07-17

### Added
- `workflow-maven-plugin`: a Maven plugin (goal `eventconductor:validate`, bound
  to `process-resources`) that validates workflow, form and rule definitions
  (JSON/YAML) against the engine's published specifications at build time and
  fails the build on any violation. It bundles the canonical schemas straight
  from the sibling engine modules so it can never drift, and adds the semantic
  checks a schema cannot express: workflow duplicate/dangling/self-referencing
  step references, cron-expression validity and JEXL parseability of
  preconditions; decision-table row arity and JEXL parseability of rule
  expressions. Configurable directories, per-type toggles, `failOnError`,
  `failOnMissing` and `skip`.

## [1.0-beta.007] - 2026-07-16

### Added
- `RULE` step type backed by a new rule-engine catalog and embeddable
  rule-runtime: a step references a `ruleId` and the engine dispatches it for
  evaluation.
- Forms **Tasks v2**: the pending-tasks listing renders the completion form
  inline in each row, with an optimized query behind it.
- `TIMER` step type: durably pauses a process for an ISO 8601 duration
  (`duration: "PT72H"`) or until an absolute date taken from a process variable
  (`untilVariable`), fired by the timeout scheduler in both memory and JPA modes.
- `MESSAGE` step type with correlation: a step waits (durably, dispatching no
  task) for a `MessageReceived` correlated by the process `businessKey` or a
  `correlationExpression`; the message variables merge into the process.
- Cron-scheduled process starts: a definition can declare a `cronExpression`
  and the engine creates instances at each occurrence with deterministic
  business keys so multiple pods never duplicate an occurrence. New
  `workflow.cron-scan-interval-ms` and `workflow.cron-enabled` properties.
- Built-in process analytics (`ProcessAnalyticsService`): per definition and
  time window, instance counts by status, completion/error/cancellation rates,
  throughput per day and average/p95 durations per process and per step with a
  bottleneck flag — in all deployment modes. Surfaced in an **Analytics** UI
  page and the `getWorkflowAnalytics` / `findBottleneck` MCP tools. Step
  executions record `finishedAt` on terminal status (Flyway `V3`).
- Public test specification ([TESTING.md](./TESTING.md)) and the matching
  suites: an embedded/JPA end-to-end suite (`workflow-e2e`) covering
  orchestration semantics, failure handling, timers, message correlation,
  cron starts, idempotency, the JEXL sandbox and analytics; a JPA durability
  suite driving the real outbox relay and JDBC advisory locks on H2; a
  single-JVM crash-recovery test; and a Docker-gated distributed suite
  (`workflow-dist-e2e`, `dist-e2e` profile) over real Kafka + PostgreSQL via
  Testcontainers — distributed happy path, crash recovery, two-pod dispatch
  exclusivity, worker-crash redelivery and a 500-instance load smoke.

### Changed
- Bumped the Mateu UI dependency to `3.0-alpha.243` and centralized it as a
  `mateu.version` property.

### Fixed
- A process whose step exhausted its retries is now reported as `ERROR`
  instead of being falsely marked `COMPLETED`, and successors of a failed
  step no longer run.
- Precondition (JEXL) expressions are evaluated in a sandbox
  (`JexlPermissions.RESTRICTED`, blocking reflection and system access) and
  now fail closed — an evaluation error means the guarded step does not run.
- Outbox relays publish before marking a message `Sent` (at-least-once);
  messages that cannot be loaded are parked as `Error` rather than retried
  forever, and message types are validated against an `io.mateu.*` allowlist.
- Process creation deduplicates by `businessKey`, so a redelivered creation
  event no longer creates a duplicate process.
- Cancellation marks the process `CANCELLED` first so the orchestration loop
  cannot dispatch new steps mid-cancellation, and late worker reports for
  steps already in a terminal state are ignored.
- Step timeouts now fire in memory-persistence mode, not only in JPA mode.
- Definition validation rejects duplicate step ids and dangling
  `preconditionStepId` / `compensationStepId` references.
- Relay and scheduler threads are daemon threads with configurable poll
  intervals.

## [1.0-beta.006] - 2026-07-02

### Added
- Spring Boot Actuator with Prometheus, health, and info endpoints in
  `workflow-engine` and `forms-engine`.
- Custom Micrometer metrics for processes by status, step executions and
  retries.
- Spring Security with HTTP Basic on REST endpoints, configurable via
  application properties. Actuator health/info remain public.
- Flyway migrations for the workflow and forms schemas. Default `ddl-auto`
  is now `validate` in production profiles.
- Logstash-encoder structured JSON logging via `application-prod.yaml`.
- LICENSE (MIT), SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md and
  CHANGELOG.md.
- Dependabot configuration for Maven, GitHub Actions, Docker and npm.
- Build status, CodeQL and license badges in the README.
- Helm chart hardening: external secrets, NetworkPolicy, PodDisruptionBudget,
  Ingress template and Actuator-based probes.
- New PR CI workflow that runs `mvn verify` (unit + integration tests +
  JaCoCo coverage check) and Trivy container scans.
- Renamed misspelled `buid-and-publish.yml` to `build-and-publish.yml` and
  enabled running the test suite during release builds.

### Changed
- `workflow.mode` now defaults to `embedded` (previously `kafka`), mirroring
  `workflow.persistence` which defaults to `memory`. Apps start fully
  in-process with no external dependencies and opt into JPA/Kafka as they
  scale. The standalone distributed apps set `kafka`/`jpa` explicitly.
- Bumped the Mateu UI dependency to `3.0-alpha.222`.
- Dockerfiles run as a non-root `app` user (UID 10001).
- `docker-compose.yml` split into `docker-compose.dev.yml` (with default
  passwords) and `docker-compose.yml` (with required env vars).

### Fixed
- Quote the reserved SQL column `values` in `FormExecutionEntity` so schema
  creation succeeds on H2 and PostgreSQL.

### Security
- All REST endpoints in the orchestrator and forms standalone apps now
  require authentication by default.
- Bumped Eclipse JGit to `6.10.1.202505221210-r` to fix the XXE vulnerability
  CVE-2025-4949 (GHSA-vrpq-qp53-qv56).

## [Earlier]

EventConductor pre-1.0 snapshots. See the `git log` for individual commits.
