# EventConductor Maven Plugin

Validates EventConductor **workflow**, **form** and **rule** definitions (JSON or YAML)
against their published specifications **at build time**, so a malformed definition fails the
build in the PR instead of at runtime when the engine loads it.

It reuses the *exact* schemas shipped by the engine modules
(`workflow-definition-schema.json`, `form-schema.json`, `rule-schema.json` — bundled straight
from the sibling modules at build time, so the plugin can never drift from the engine), plus
the semantic checks a JSON schema cannot express:

- **Workflows** — duplicate step ids, self-referencing / dangling `preconditionStepId` and
  `compensationStepId`, cron-expression validity, and JEXL parseability of
  `preconditionExpression`.
- **Rules** — decision-table row arity (one `when` cell per input, one `then` cell per
  output) and JEXL parseability of expressions.
- **Forms** — JSON-schema validation.

## Usage

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

By default the `validate` goal binds to the `process-resources` phase and scans, under the
project resources, the same layout the engine loads from the classpath:

```
src/main/resources/
  workflows/**/*.{json,yaml,yml}
  forms/**/*.{json,yaml,yml}
  rules/**/*.{json,yaml,yml}
```

Run it directly with `mvn eventconductor:validate`, or let it run in the build
(`mvn verify`). On any violation the build fails with a per-file report:

```
EventConductor definition validation found 2 problem(s):

src/main/resources/workflows/order.yaml:
  - 'not a cron' is not a valid cron expression
  - Step 's1' references unknown precondition step 'nope'.
```

## Configuration

| Parameter | Property | Default | Description |
|---|---|---|---|
| `workflowsDirectory` | | `${basedir}/src/main/resources/workflows` | Where workflow definitions live. |
| `formsDirectory` | | `${basedir}/src/main/resources/forms` | Where form definitions live. |
| `rulesDirectory` | | `${basedir}/src/main/resources/rules` | Where rule definitions live. |
| `validateWorkflows` | `eventconductor.validate.workflows` | `true` | Validate workflows. |
| `validateForms` | `eventconductor.validate.forms` | `true` | Validate forms. |
| `validateRules` | `eventconductor.validate.rules` | `true` | Validate rules. |
| `failOnError` | `eventconductor.validate.failOnError` | `true` | Fail the build on violations (otherwise warn). |
| `failOnMissing` | `eventconductor.validate.failOnMissing` | `false` | Fail if a configured directory has no definitions. |
| `skip` | `eventconductor.validate.skip` | `false` | Skip validation entirely. |

Example — validate only workflows, in a custom directory, without failing the build:

```xml
<configuration>
  <workflowsDirectory>${project.basedir}/definitions/workflows</workflowsDirectory>
  <validateForms>false</validateForms>
  <validateRules>false</validateRules>
  <failOnError>false</failOnError>
</configuration>
```
