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

- **Precondition cycles are rejected at load.** `WorkflowDefinition.checkInvariants()` throws
  on a `preconditionStepId` cycle (A waits for B waits for … A would deadlock), plus duplicate
  step ids, self-preconditions/self-compensation, unknown referenced step ids, TIMER without
  `duration`/`untilVariable`, and WAIT_FOR_MESSAGE / SEND_MESSAGE without `messageName` or
  `correlationExpression`.

- **`correlationExpression` is required on both message step types.** The old
  defaults-to-businessKey behavior is gone for new definitions — write
  `"correlationExpression": "businessKey"` explicitly. (Only legacy steps persisted
  before the `MESSAGE` → `WAIT_FOR_MESSAGE` rename keep the businessKey fallback.)

- **SEND_MESSAGE fails loud, not closed.** A missing `messageName`/`correlationExpression` or
  a correlation key that cannot be evaluated puts the step in `ERROR` (normal retry/
  compensation pipeline) — unlike precondition guards, which fail closed silently. And
  messages are still **not buffered**: a sent message matching no waiting process is
  discarded, so sequence the sender after the receiver is already waiting.

- **Ordering by array position.** Steps run by `preconditionStepId`, not the order in the
  array. A step with no precondition starts immediately (possibly in parallel with others).

- **Missing or multiple `END`.** Exactly one `END` per workflow. If you have parallel
  branches, funnel them through a `JOIN` before the `END`, or the process never completes.

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
  step; the compensation step itself is a normal `ACTION` (usually with no precondition).

- **Wrong default mode assumption.** Defaults are `workflow.mode=embedded` +
  `workflow.persistence=memory` (in-process, no external deps). Set `kafka`/`jpa` explicitly
  for distributed/persistent deployments.
