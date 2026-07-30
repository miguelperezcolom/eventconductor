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

- **The roots rule breaks old definitions.** Every step with no preconditions must be a
  `START` or a `WAIT_FOR_MESSAGE` — a definition whose first step is a plain `ACTION` with no
  precondition is now **rejected at load**. Migration: add one `START` step and point the old
  first steps at it (`"preconditionStepId": "start"`). A `START` itself must have NO
  preconditions.

- **`parallel` is ignored.** It is deprecated (kept only for deserialization): every step
  whose preconditions are met starts concurrently, always. Don't emit it in new definitions;
  removing it changes nothing.

- **JOIN without `preconditionStepIds` is no barrier.** JOIN's barrier IS its multiple
  preconditions: list every branch end in `preconditionStepIds`. A JOIN with a single
  precondition (or the singular `preconditionStepId`) waits for that one step only.

- **Compensation steps need a precondition too** (roots rule). Anchor them to the step they
  compensate plus `"preconditionExpression": "false"` so the dataflow never starts them; the
  compensation pipeline starts them directly and does not evaluate the guard.

- **PROCESS cancellation does not propagate.** Cancelling the parent process leaves an
  already-started child process running (known limitation). Child ERROR/CANCELLED does
  propagate up: the parent PROCESS step goes `ERROR`.

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
  tracking work. An unhandled throw is not the same as a reported failure.

- **Forgetting `RUNNING` on long tasks.** Report `RUNNING` periodically to reset the timeout
  clock, or configure a `timeout` large enough for the work.

- **Compensation misconfigured.** `compensationStepId` requires `rollbackable: true` on the
  step; the compensation step itself is a normal `ACTION` anchored to the step it compensates
  with `"preconditionExpression": "false"` (see the roots-rule bullet above).

- **Wrong default mode assumption.** Defaults are `workflow.mode=embedded` +
  `workflow.persistence=memory` (in-process, no external deps). Set `kafka`/`jpa` explicitly
  for distributed/persistent deployments.
