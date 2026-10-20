# Gotchas, validation errors, upgrades, modes

## Definition mistakes

- **Missing `name` on a step** — the schema requires `id`, `type` AND `name` on every step (the Maven
  plugin and the engine's import refuse the file). Unknown keys are refused too (`retires: 3` fails).
- **Two `START`s** — at most one START per definition; branch after it with a FORK.
- **A step with no way in** — every non-START step needs a precondition, except a compensation step
  and a `WAIT_FOR_MESSAGE` that begins a flow.
- **Array order means nothing** — steps run by preconditions; all eligible steps run concurrently.
  `parallel` is ignored.
- **Cycles** — rejected; use `repeat` (one step) or `loopBack` (a section).
- **AND-JOIN after a CHOICE or guarded branch** — the branches not taken never complete, the JOIN never
  fires, and the process completes implicitly, cancelling everything after it. Use `joinType: XOR`
  after a CHOICE; the plugin warns about JOINs on guarded steps.
- **CHOICE without a default** — if no guard holds, no branch runs. Leave one link unguarded.
- **Guarded chain with no alternative** — a step whose `DISCARD` guard is false is skipped, and so is
  everything that depends on it. `onFalse: WAIT` instead holds the step and keeps the process open.
- **Compensation step with preconditions** — compensation steps must have none (older definitions use
  `preconditionExpression: "false"`; still works). Without `compensable: true` on the step being undone
  nothing is compensated. The failing step itself is never compensated.
- **`onTimeoutStepId` target reachable on the happy path** — guard it with
  `preconditions: [{stepId: <origin>, expression: "false"}]` so only the timeout route reaches it.
- **Request then WAIT_FOR_MESSAGE** — a fast reply arrives before the wait is armed and is dropped
  (messages are not buffered). Use `awaitReply` on the request step (or FORK the wait and the request).
  `messageName`/`correlationExpression` written directly on an HTTP_CALL/PUBLISH_EVENT are ignored (warning).
- **Correlating on an id the other side returns** — choose the correlation id before sending (business key).
- **Business keys are unique across ALL workflows** — include the workflow in trigger keys
  (`booking-onboarding-${data.bookingId}`). Reusing a key needs `businessKeyPolicy`.
- **Variables are strings** — JEXL coerces numbers (`amount > 1000`); structures are JSON text; a template
  leaf that is exactly `${expr}` keeps its type.
- **forEach outputs are not on the process** — read the `output` variable.
- **`maxIterations` is required** on `repeat`/`loopBack`; exceeding any cap fails the step.
- **PROCESS children** get ALL parent variables and return only `outputVariables`; their business key is
  `parent:<stepExecutionId>`.
- **USER_TASK** needs forms-engine with the form loaded from `forms.directory-import.directories` or git —
  forms are NOT read from the classpath. In tests, `completeUserTask` needs no forms-engine.
- **Secrets in definitions** — literal HTTP credentials, `${secret:}` outside an inline `auth` block, or an
  LLM key in a definition fail validation.
- **Embedded `topic`** — ignored; one `EmbeddedTaskExecutor` gets every task (no per-topic beans).
  A hand-written executor also receives the built-in HTTP/LLM tasks — use `TaskHandler`s (worker-embedded) then.
- **Reporting with `stepId`** instead of `taskExecutionId` silently does nothing.
- **Throwing in a Kafka worker** instead of replying `ERROR` — use `WorkerReply.failed(…, reason)`.
- **A paused definition still creates instances** (born `PAUSED`); `DISABLE` it to refuse them.

## Common validation errors → fix

Messages from the JSON schema come in the JVM's language (e.g. Spanish `no se encontró la propiedad
requerida 'name'`); set `MAVEN_OPTS=-Duser.language=en` to get English. The engine's own messages are English.

| Message (engine / `mvn eventconductor:validate`) | Fix |
|---|---|
| `/steps/N: required property 'name' not found` (localized) | add `name` to the step |
| `/steps/N: property 'x' is not defined in the schema` (localized) | typo / field on the wrong level (check the schema) |
| `A workflow can have at most one START step, but found N.` | keep one START |
| `Step 'X' has no preconditions and is not a START, a WAIT_FOR_MESSAGE, or another step's compensation — nothing would ever start it.` | add `preconditionStepId` |
| `Step 'X' references unknown precondition step 'Y'.` / `unknown compensation step` | fix the id |
| `Duplicate step id 'X'.` | rename |
| `START step 'X' cannot have preconditions.` | remove them |
| `Timer step 'X' must define a duration, an untilVariable or an until.` | add one (not `0`) |
| `Message step 'X' must define a messageName.` / `a correlationExpression.` | add both (`correlationExpression: businessKey`) |
| `Process step 'X' must define a childWorkflowDefinitionId.` | add it (≠ own id) |
| `Step 'X' declares a deadline, but a <TYPE> step does not wait for anything that could miss it.` | remove `deadline` |
| `HTTP_CALL step 'X' must define exactly one of connection and url.` / `path is only for a connection; put it in the url.` | pick one |
| `retryOn value 'v' is not 4xx, 5xx, io or a status code.` | fix `retryOn` |
| `Step 'X' awaitReply: only an HTTP_CALL or a PUBLISH_EVENT awaits a reply.` | move to a WAIT_FOR_MESSAGE |
| `Step 'X' declares forEach together with awaitReply…` | run a PROCESS per item |
| `Step 'X' loopBack must define maxIterations…` / `goes back to 'Y', which is not upstream of it` | add the cap / point upstream |
| `… must declare exactly one of step, http, process and task.` | one kind per tool |
| `Step 'X' declares both a deadline and an escalation` | keep one |
| `REPLY step 'X' declares more than one of replyVariables, replyExpression and replyTemplate` | keep one |
| `…sync-invocable (syncInvocation.enabled) but no REPLY step…` | add a reachable REPLY |
| `Unterminated ${ at position N` | close the template |
| `'expr' is not a valid cron expression` | Spring cron has 6 fields (`0 0 9 * * *`) |
| `…declares sensitive variables […] but no encryption key is configured` | set `workflow.encryption.keys.<id>` |
| search attribute / correlation / lock / startPolicy key computed from a sensitive variable | use a non-sensitive variable |

## What each mode supports

| | embedded + memory | embedded + jpa | kafka + jpa | sharded (kafka + jpa per shard) |
|---|---|---|---|---|
| use for | tests, local, embedding | single node, durable | multi-pod production | very large scale |
| state survives restart | no | yes | yes | yes |
| definitions from classpath / directory / git | yes | yes (+ versions history) | yes | yes |
| migration to older/other versions | current version only | yes | yes | yes |
| search attributes | computed on read | stored (V48) | stored | per shard |
| batch operations, retention purge | in heap | yes, one node at a time | yes | per shard |
| HTTP workers | yes | yes (`http_task`) | yes | per shard |
| event triggers consumed from Kafka | — (REST/embedded/loopback) | — | needs `inbound-events-kafka` | yes |
| sync invocation across nodes | — | PostgreSQL NOTIFY | PostgreSQL NOTIFY (else polling) | `ON_ANOTHER_SHARD` refusal |

Flyway migrations target PostgreSQL and H2; MariaDB/Oracle use `ddl-auto` and hand-made indexes.
With jpa always add `flyway-core` (without it the engine warns and runs without indexes).

## Kafka consumer behaviour (on main after 2.33.0 — #565, next release)

- A retryable failure (optimistic-lock conflict, deadlock, DB/broker away) is **redelivered with
  exponential back-off, without limit**, from the failed record; earlier records of the batch are
  committed. Nothing is discarded; only permanently defective records go to `dead-letter`.
- Engine: `workflow.consumer.retry.{initial-interval: 100ms, multiplier: 2.0, max-interval: 10s,
  max-attempts: 0 (=unlimited; >0 parks on dead-letter), warn-after: 10}`. Workers / rule runtime / forms
  engine: `eventconductor.worker.consumer.retry.*`. Projector: `projector.consumer.retry.*`. Inbound
  events: `workflow.events.consumer.retry-backoff`.
- Watch `eventconductor.events.redelivered{topic,cause}` (a trickle is normal under parallel forEach;
  a climbing rate holds a partition) and `eventconductor.worker.tasks.redelivered`.
- Up to 2.32.0 the default batch error handler discarded a batch after 9 instant retries — fan-out
  processes could get stuck with iterations `PENDING`.
- Don't rewire the engine's bindings; if you do: `batch-mode=true` and a distinct group for
  `consumeUpstream-in-0` and `consumeOutbox-in-0`.

## Breaking changes and upgrade notes (2.27 → 2.33)

| Version | What changed | Action |
|---|---|---|
| 2.27.0 | `${secret:NAME}` resolves only from `workflow.http.secrets.<NAME>` | list secrets, or `workflow.http.secrets-from-environment=true` |
| 2.27.0 | `/workflow/api/**` refuses anonymous (401) | `workflow.message-api.api-key` / Spring Security / `allow-anonymous=true` (dev); webhook gateway `rest` transport needs `ENGINE_API_KEY` |
| 2.27.0 | migrations V43 (analytics), V44 (outbox index, not concurrent) | with `workflow.analytics.rollup=true` stop all 2.26 pods first (Recreate); big outbox: pre-create `idx_outbox_key_status_ts` |
| 2.28.0 | admin UI, MCP, rule reads refuse anonymous (403); `workflow.ui.enabled` | Spring Security or `allow-anonymous=true`; `spring.ai.mcp.server.enabled=false` if unused |
| 2.28.0 | embedded apps mounting the console need `@EnableWorkflowUi` (Mateu no longer scans `io.mateu`) | add it; no `@ComponentScan` on the app class |
| 2.28.0 | HTTP_CALL credentials bound to hosts (`HTTP_FORBIDDEN_AUTH`) | `workflow.http.auth.<name>.hosts`, `allowed-hosts`, or call via a `connection` |
| 2.28.0 | unsigned git-import webhooks refused | set `workflow.git-import.webhook-secret` (and `forms.`/`rules.`/`tasks.` ones) |
| 2.28.0 | standalone apps no longer print a generated admin password | set `SECURITY_PASSWORD` |
| 2.28.0 | rules gRPC off by default | `rules.source=rest`, or `RULES_GRPC_ENABLED=true` behind mTLS |
| 2.28.0 | forms trust a forwarded bearer token only with `workflow.security.trust-forwarded-token=true` | set it on the forms pod behind a verifying gateway |
| 2.28.0 | V45 backfills `process_entity.finished` (row locks), V46 | run in a quiet window |
| 2.28.0 | `TaskExecutionRequested.attempt` / `TaskStatusChanged.attempt` appended | record patterns must add the component |
| 2.29.0 | V47; `maxSuccessfulExecutions`/`defaultMaxStepExecutions` now enforced | check loops against the caps |
| 2.30.0 | V48–V50; incident "Retry all" is an async batch | — |
| 2.31.0 | forms V8; claimed tasks are `ASSIGNED` | **rolling: upgrade forms engines and workers before any definition uses `escalation`**; inbox filters read `ASSIGNED` |
| 2.32.0 | V53; `sensitiveVariables` needs encryption keys; Kafka/HTTP workers get ciphertext unless they share keys | configure `workflow.encryption.*` on engine (and workers that read them) |
| 2.33.0 | V55, V56 (brief exclusive lock per ALTER), V56.1 (concurrent indexes) | busy DB: `lock_timeout` or off-peak |
| 2.33.0 | `TaskExecutionRequested.priority` appended | record patterns add the component |
| 2.33.0 | rolling from 2.32 | **don't import definitions using `CONTINUE_AS_NEW` or a reusing `businessKeyPolicy` until every pod runs 2.33** |
| 2.26.x | own kafka-mode apps using triggers/`onEvent` | add `io.mateu.workflow:inbound-events-kafka` |

General rolling-upgrade order: database migrations run on the first new engine pod; upgrade **forms
engines and workers before engines start emitting new message types**, and **all engine pods before
importing definitions that use new DSL features**.
