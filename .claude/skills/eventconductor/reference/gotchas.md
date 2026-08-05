# Common mistakes

- **Expecting a `SKIPPED` status.** There is none. A step whose `preconditionExpression` is
  falsy is simply never run: it stays `CREATED` and is flipped to `CANCELLED` when the `END`
  step fires. **Trap:** dependent steps require their `preconditionStepId` step to be
  `COMPLETED`, so a never-run step permanently blocks every step chained after it — that whole
  branch dies silently. Give conditional branches an alternative path to `END`.

- **Importing the wrong `Variable`.** Two records share the name and shape:
  `io.mateu.workflow.dtos.Variable` (events: `TaskExecutionRequested`, `TaskStatusChanged`,
  `ProcessCreationRequested`) vs `io.mateu.workflow.domain.aggregates.Variable`
  (`UpdateStepExecutionCommand`). Mixing them fails to compile; check the import.

- **The roots rule breaks old definitions.** A step with no preconditions does not run — it
  must be a `START`, a `WAIT_FOR_MESSAGE` beginning a flow, or another step's
  `compensationStepId` (the rollback pipeline starts that one). Anything else with no
  preconditions is **rejected at load**: nothing would ever start it. Migration: add one
  `START` step and point the old first steps at it (`"preconditionStepId": "start"`). A
  `START` itself must have NO preconditions.

- **`parallel` is ignored.** It is deprecated (kept only for deserialization): every step
  whose preconditions are met starts concurrently, always. Don't emit it in new definitions;
  removing it changes nothing.

- **JOIN without `preconditionStepIds` is no barrier.** JOIN's barrier IS its multiple
  preconditions: list every branch end in `preconditionStepIds`. A JOIN with a single
  precondition (or the singular `preconditionStepId`) waits for that one step only.

- **Compensation steps declare no preconditions.** They are named by the step they undo and
  started directly by the rollback pipeline, so they need no way in — and must not be given
  one. (Older definitions anchor them to the step they compensate with
  `"preconditionExpression": "false"`; that still works. An anchor written *without* the
  guard does not: it is a live branch of the happy path.)

- **PROCESS cancellation propagates both ways.** Child ERROR/CANCELLED → parent PROCESS step
  `ERROR`; and a parent step ending CANCELLED/ERROR/TIMEOUT (retries exhausted) cancels a
  still-running child, cascading to grandchildren. While retries remain the child is NOT
  cancelled — a retried PROCESS step re-attaches to the same child via the businessKey dedupe.

- **A JOIN on a guarded branch can be cancelled silently.** If any branch feeding a JOIN
  carries a falsy `preconditionExpression`, the JOIN never fires and implicit completion
  cancels it and everything after it — the process still completes successfully. The Maven
  plugin emits a build-time warning for JOINs whose direct precondition steps carry guards.

- **Child processes get the businessKey `parent:<stepExecutionId>`**, not the parent's
  business key — it is deterministic so redelivered creation events are deduped. Look up
  children by that convention (or by `Process.parentStepExecutionId`). Only the child
  variables named in `outputVariables` come back to the parent; absent = none.

- **Precondition cycles are rejected at load.** `WorkflowDefinition.checkInvariants()` runs a
  DFS over the multi-edge precondition graph (steps may declare several preconditions) and
  throws on any cycle (A waits for B waits for … A would deadlock), plus duplicate step ids,
  self-preconditions/self-compensation, unknown referenced step ids, the roots rule, TIMER
  without `duration`/`untilVariable`, WAIT_FOR_MESSAGE / SEND_MESSAGE without `messageName` or
  `correlationExpression`, and PROCESS without a `childWorkflowDefinitionId` (or with itself
  as the child).

- **`correlationExpression` is required on both message step types.** The old
  defaults-to-businessKey behavior is gone for new definitions — write
  `"correlationExpression": "businessKey"` explicitly. (Only legacy steps persisted
  before the `MESSAGE` → `WAIT_FOR_MESSAGE` rename keep the businessKey fallback.)

- **SEND_MESSAGE fails loud, not closed.** A missing `messageName`/`correlationExpression` or
  a correlation key that cannot be evaluated puts the step in `ERROR` (normal retry/
  compensation pipeline) — unlike precondition guards, which fail closed silently. And
  messages are still **not buffered**: a sent message matching no waiting process is
  discarded, so sequence the sender after the receiver is already waiting.

- **Pause does not stop in-flight work — only successors are held.** A `PAUSED` process
  still accepts worker reports (steps complete, output variables merge) and correlated
  messages (WAIT_FOR_MESSAGE steps complete); what freezes is the frontier — no successor
  starts until resume — plus the timer/timeout clocks and blocking-error handling. And the
  clock freeze is implemented by **shifting `startedAt` forward** by the pause duration on
  resume, so don't treat a step's `startedAt` as an immutable audit timestamp: on a
  process that was paused it has moved.

- **A paused definition still creates instances — born `PAUSED`.** Pausing a workflow
  definition (runtime `paused` flag, orthogonal to `DRAFT`/`ACTIVE`/...) does NOT reject
  new instances: creation requests — cron occurrences included — still create the process
  and its steps, in status `PAUSED`, and they only start when the definition is resumed.
  If you want new instances rejected, `DISABLE` the definition instead of pausing it.

- **Ordering by array position.** Steps run by preconditions (`preconditionStepIds` /
  `preconditionStepId`), not the order in the array — and ALL eligible steps start
  concurrently. Sequencing only exists where you declare it.

- **Missing or multiple `END`.** Exactly one `END` per workflow. If you have parallel
  branches, funnel them through a `JOIN` (listing all branch ends in `preconditionStepIds`)
  before the `END`, or the process never completes.

- **Reporting with `stepId` instead of `taskExecutionId`.** `UpdateStepExecutionCommand` and
  `TaskStatusChanged` take the `taskExecutionId` from the `TaskExecutionRequested`, not the
  workflow `stepId`. Using the wrong id silently fails to advance the step.

- **Expecting typed variables.** Variables are strings. JEXL comparisons operate on the
  string value — `"amount > 1000"` works because JEXL coerces, but `"amount"` must be numeric
  text. Don't rely on booleans/objects.

- **Omitting `topic` in Kafka mode.** `ACTION` steps need a `topic` in Kafka mode. In embedded
  mode `topic` is ignored (single `EmbeddedTaskExecutor`, or per-topic named beans).

- **USER_TASK without forms-engine.** `USER_TASK` steps require the `forms-engine` dependency
  and a form whose id matches `formId`.

- **Worker throws instead of reporting ERROR.** Catch exceptions and report
  `StepExecutionStatus.ERROR` / `TaskStatus.ERROR` with a log message, so retries and error
  tracking work. An unhandled throw is not the same as a reported failure — in embedded mode the
  engine now fails the step for you rather than leaving it `PENDING` for ever, but with no
  variables and a message you did not choose. In Kafka mode nobody catches it at all.

- **An embedded worker that blocks stops the engine.** Embedded dispatch is inline by default,
  and under `jpa` persistence the calling thread is the single `embedded-outbox-relay` — the only
  thread advancing every process in the JVM. A worker waiting on an HTTP call with no timeout
  freezes all of them, and processes created afterwards show every step in `CREATED`
  ("waiting for its preconditions"). Set `workflow.embedded.worker-threads` to hand tasks to a
  pool, and give ACTION steps a `timeout` before you do.

- **Forgetting `RUNNING` on long tasks.** Report `RUNNING` periodically to reset the timeout
  clock, or configure a `timeout` large enough for the work.

- **Compensation misconfigured.** `compensationStepId` requires `rollbackable: true` on the
  step; the compensation step itself is a normal `ACTION` with no preconditions at all (see
  the compensation bullet above).

- **Wrong default mode assumption.** Defaults are `workflow.mode=embedded` +
  `workflow.persistence=memory` (in-process, no external deps). Set `kafka`/`jpa` explicitly
  for distributed/persistent deployments.
