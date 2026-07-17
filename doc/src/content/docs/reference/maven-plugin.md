---
title: "Maven Plugin: validating definitions"
description: Validate EventConductor workflow, form and rule definitions (JSON/YAML) at build time with the workflow-maven-plugin, failing the build on any violation.
---

Because EventConductor definitions are **data owned by developers** — flat JSON/YAML files
in a pull request — you can validate them the same way you validate any other source: at
build time. The `workflow-maven-plugin` checks every workflow, form and rule definition
against the exact specifications the engine enforces, and **fails the build** on any
problem, so mistakes are caught in the PR rather than at runtime when the engine loads them.

## What it checks

The plugin bundles the *same* JSON schemas the engine ships
(`workflow-definition-schema.json`, `form-schema.json`, `rule-schema.json` — copied straight
from the engine modules at build time, so it can never drift), plus the semantic checks a
schema cannot express:

- **Workflows** — schema, duplicate step ids, self-referencing / dangling
  `preconditionStepId` and `compensationStepId`, cron-expression validity, and JEXL
  parseability of `preconditionExpression`.
- **Rules** — schema, decision-table row arity (one `when` cell per input, one `then` cell
  per output) and JEXL parseability of expressions.
- **Forms** — schema validation.

## Setup

```xml
<plugin>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-maven-plugin</artifactId>
  <version>1.0-beta.008</version>
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
