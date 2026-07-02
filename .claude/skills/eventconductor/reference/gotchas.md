# Common mistakes

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
