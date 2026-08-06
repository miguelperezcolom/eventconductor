---
title: "Maven Plugin: validating definitions"
description: Validate EventConductor workflow, form and rule definitions (JSON/YAML) at build time with the workflow-maven-plugin, failing the build on any violation.
---

Because EventConductor definitions are **data owned by developers** — flat JSON/YAML files
in a pull request — you can validate them the same way you validate any other source: at
build time. The `workflow-maven-plugin` checks every workflow, form and rule definition
against the same JSON schemas the engine ships plus most of the engine's semantic checks,
and **fails the build** on any problem, so most mistakes are caught in the PR rather than
at runtime when the engine loads them (see [what it checks](#what-it-checks) for the
checks that still only happen at engine load).

## What it checks

The plugin bundles the *same* JSON schemas the engine ships
(`workflow-definition-schema.json`, `form-schema.json`, `rule-schema.json` — copied straight
from the engine modules at build time, so it can never drift), plus the semantic checks a
schema cannot express:

- **Workflows** — schema, duplicate step ids, self-referencing / dangling precondition
  (`preconditionStepIds` / `preconditionStepId`) and `compensationStepId` references, the
  entry-point rule (every step with no preconditions must be a `START` or a
  `WAIT_FOR_MESSAGE`, and a `START` must have none), precondition-cycle detection (DFS over
  the multi-edge precondition graph), the `PROCESS` child id (`childWorkflowDefinitionId`
  present and different from the workflow's own id), cron-expression validity, and JEXL
  parseability of `preconditionExpression` and `correlationExpression`. It also emits
  **build-time warnings** (logged, never failing the build) for risky-but-legal patterns:
  currently, a `JOIN` waiting directly on a guarded step — if the guard is false the join
  never fires and the flow beyond it is silently cancelled.
- **Rules** — schema, decision-table row arity (one `when` cell per input, one `then` cell
  per output) and JEXL parseability of expressions.
- **Forms** — schema validation.

:::note[Checks that only happen at engine load]
A few of the engine's invariants (`WorkflowDefinition.checkInvariants()`) are **not**
replicated by the plugin and will only fail when the engine loads the definition:

- **TIMER value checks** — the schema only requires that `duration` or `untilVariable` is
  *present*; a `duration` of `0` (with no `untilVariable`) passes the build but is rejected
  at load.
- **Message value checks** — the schema only requires that `messageName` and
  `correlationExpression` are *present* on `WAIT_FOR_MESSAGE` / `SEND_MESSAGE` steps; a
  blank value passes the build but is rejected at load.
:::

## Setup

```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <version>1.0-beta.025</version>
  <executions>
    <execution>
      <goals>
        <goal>validate</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

The `validate` goal binds to `process-resources` and scans, under the project resources, the
same layout the engine loads from the classpath:

```
src/main/resources/
  workflows/**/*.{json,yaml,yml}
  forms/**/*.{json,yaml,yml}
  rules/**/*.{json,yaml,yml}
```

Run it in the build (`mvn verify`) or on demand with `mvn eventconductor:validate`. On a
violation the build fails with a per-file report:

```
EventConductor definition validation found 2 problem(s):

src/main/resources/workflows/order.yaml:
  - 'not a cron' is not a valid cron expression
  - Step 's1' references unknown precondition step 'nope'.
```

## Configuration

| Parameter | Property | Default | Description |
|---|---|---|---|
| `workflowsDirectory` | | `${basedir}/src/main/resources/workflows` | Workflow definitions directory. |
| `formsDirectory` | | `${basedir}/src/main/resources/forms` | Form definitions directory. |
| `rulesDirectory` | | `${basedir}/src/main/resources/rules` | Rule definitions directory. |
| `validateWorkflows` | `eventconductor.validate.workflows` | `true` | Validate workflows. |
| `validateForms` | `eventconductor.validate.forms` | `true` | Validate forms. |
| `validateRules` | `eventconductor.validate.rules` | `true` | Validate rules. |
| `failOnError` | `eventconductor.validate.failOnError` | `true` | Fail the build on violations (otherwise warn). |
| `failOnMissing` | `eventconductor.validate.failOnMissing` | `false` | Fail if a configured directory has no definitions. |
| `skip` | `eventconductor.validate.skip` | `false` | Skip validation entirely. |
