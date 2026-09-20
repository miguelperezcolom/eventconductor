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
  (`preconditions` / `preconditionStepIds` / `preconditionStepId`) and `compensationStepId`
  references, the
  entry-point rule (every step with no preconditions must be a `START` or a
  `WAIT_FOR_MESSAGE`, and a `START` must have none), precondition-cycle detection (DFS over
  the multi-edge precondition graph), the `PROCESS` child id (`childWorkflowDefinitionId`
  present and different from the workflow's own id), cron-expression validity, and JEXL
  parseability of `preconditionExpression`, the conditions on `preconditions` links, and
  `correlationExpression`. It also emits
  **build-time warnings** (logged, never failing the build) for risky-but-legal patterns:
  currently, a `JOIN` waiting directly on a guarded step — if the guard is false the join
  never fires and the flow beyond it is silently cancelled.
- **Rules** — schema, decision-table row arity (one `when` cell per input, one `then` cell
  per output) and JEXL parseability of expressions.
- **Forms** — schema validation.
- **Tasks** — schema validation of each `.ectask`, plus cross-file checks: an `id` belongs to
  exactly one `group`, no two files define the same `id@version`, and every task an ACTION step
  references exists (with the pinned version, if one is given). Checking that a referenced task's
  `required` inputs are actually reachable in the graph (start variables, branches, JOINs) is a
  documented **TODO** — it needs the same dataflow analysis the engine does at runtime.

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
  <version>2.1.1</version>
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
  workflows/**/*.{ec,ecform,ecrule,ectask,json,yaml,yml}
  forms/**/*.{ec,ecform,ecrule,ectask,json,yaml,yml}
  rules/**/*.{ec,ecform,ecrule,ectask,json,yaml,yml}
  tasks/**/*.{ec,ecform,ecrule,ectask,json,yaml,yml}
```

The same extensions the engine imports. `.ec`, `.ecform` and `.ecrule` are what the graph editor and the
two IDE plugins write, and they were not collected before 2.2.3 — a repository of them was walked,
nothing was found, and the build passed. Anything that is not `.json` is read by the YAML parser,
which reads JSON too, so an `.ec` holding either parses.

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
| `tasksDirectory` | | `${basedir}/src/main/resources/tasks` | Task contracts directory. |
| `validateWorkflows` | `eventconductor.validate.workflows` | `true` | Validate workflows. |
| `validateForms` | `eventconductor.validate.forms` | `true` | Validate forms. |
| `validateRules` | `eventconductor.validate.rules` | `true` | Validate rules. |
| `validateTasks` | `eventconductor.validate.tasks` | `true` | Validate task contracts. |
| `failOnError` | `eventconductor.validate.failOnError` | `true` | Fail the build on violations (otherwise warn). |
| `failOnMissing` | `eventconductor.validate.failOnMissing` | `false` | Fail if a configured directory has no definitions. |
| `skip` | `eventconductor.validate.skip` | `false` | Skip validation entirely. |

## Generating worker types: the `generate-worker-sources` goal

The second goal turns task contracts into the Java a worker developer implements against — records,
a `<Id>V<n>Task` interface per contract version, a `TaskFailure` subclass per declared error, and a
per-group `@AutoConfiguration` that registers each implemented handler. It binds to
`generate-sources`, writes to `target/generated-sources` (added as a compile root) and a generated
resources root (the `AutoConfiguration.imports`), and never emits code you edit. Most projects get
it for free by inheriting `task-module-parent` or `task-service-parent`; see
[implementing workers](/guides/workers/#generating-a-worker-from-a-task-contract) and
[the task contract format](/reference/task-contracts/).

The contracts come from, in order of precedence: a **Maven artifact** (`definitions`, a versioned
zip — the reproducible default), a **git** checkout (`repository` + `ref`, pinned to a tag or
commit; a branch is refused unless `allowBranch`), or the project's own `tasksDirectory`. Select a
subset with `group` or `tasks`.

```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>generate-worker-sources</goal></goals>
    </execution>
  </executions>
  <configuration>
    <basePackage>com.example</basePackage>
    <group>greetings</group>
  </configuration>
</plugin>
```

| Parameter | Property | Default | Description |
|---|---|---|---|
| `basePackage` | `eventconductor.generate.basePackage` | `io.mateu.workflow.tasks.generated` | Base package; a contract's group is appended as a sub-package. |
| `tasksDirectory` | | `${basedir}/src/main/resources/tasks` | Local contracts, used when neither `definitions` nor `repository` is set. |
| `definitions` | `eventconductor.generate.definitions` | | Definitions Maven artifact `groupId:artifactId:version` (resolved as a zip). |
| `repository` | `eventconductor.generate.repository` | | Git repository URL for the definitions. |
| `ref` | `eventconductor.generate.ref` | | Git tag or commit to check out (a branch needs `allowBranch`). |
| `allowBranch` | `eventconductor.generate.allowBranch` | `false` | Allow a git branch ref (warns instead of failing). |
| `group` | `eventconductor.generate.group` | | Generate only these groups (comma-separated). |
| `tasks` | `eventconductor.generate.tasks` | | Generate only these tasks (`id` or `id@version`, comma-separated). |
| `applicationClass` | `eventconductor.generate.applicationClass` | | Also generate this `@SpringBootApplication` class (a standalone service). |
| `skip` | `eventconductor.generate.skip` | `false` | Skip generation entirely. |
