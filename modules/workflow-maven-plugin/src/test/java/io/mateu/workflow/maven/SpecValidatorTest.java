package io.mateu.workflow.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpecValidatorTest {

    private final SpecValidator validator = new SpecValidator();

    private JsonNode load(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            ObjectMapper mapper = resource.endsWith(".yaml") || resource.endsWith(".yml")
                    ? new YAMLMapper() : new ObjectMapper();
            return mapper.readTree(in);
        }
    }

    private List<String> validate(SpecValidator.Kind kind, String resource) throws Exception {
        return validator.validate(kind, load(resource));
    }

    @Test
    void validWorkflowsPass() throws Exception {
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/sequential.json")).isEmpty();
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/cron.yaml")).isEmpty();
    }

    /**
     * A definition carrying a hand-made diagram arrangement passes the build gate. The graph editor
     * writes `layout` into .ec files, and the schema root refuses anything it does not declare — so
     * without this the plugin would fail the build of every project whose workflows had been
     * arranged.
     */
    @Test
    void anArrangedWorkflowPasses() throws Exception {
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/arranged.yaml")).isEmpty();
    }

    @Test
    void validFormPasses() throws Exception {
        assertThat(validate(SpecValidator.Kind.FORM, "/valid/forms/approval.json")).isEmpty();
    }

    @Test
    void validRulesPass() throws Exception {
        assertThat(validate(SpecValidator.Kind.RULE, "/valid/rules/expression.yaml")).isEmpty();
        assertThat(validate(SpecValidator.Kind.RULE, "/valid/rules/decision-table.json")).isEmpty();
    }

    @Test
    void joinOnGuardedPreconditionWarnsButStaysValid() throws Exception {
        JsonNode document = load("/valid/workflows/join-on-guarded-branch.json");
        // Legal (fail-closed dataflow) → no violation…
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, document)).isEmpty();
        // …but exactly one warning pointing at the guarded branch.
        assertThat(validator.warnings(SpecValidator.Kind.WORKFLOW, document)).containsExactly(
                "JOIN 'join' waits on guarded step 'maybe' — if its guard is false the join"
                        + " never fires and the flow beyond it is cancelled.");
    }

    @Test
    void joinOnUnguardedPreconditionsProducesNoWarnings() throws Exception {
        JsonNode document = load("/valid/workflows/join-unguarded.json");
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, document)).isEmpty();
        assertThat(validator.warnings(SpecValidator.Kind.WORKFLOW, document)).isEmpty();
    }

    @Test
    void nonWorkflowKindsNeverWarn() throws Exception {
        assertThat(validator.warnings(SpecValidator.Kind.FORM, load("/valid/forms/approval.json"))).isEmpty();
        assertThat(validator.warnings(SpecValidator.Kind.RULE, load("/valid/rules/decision-table.json"))).isEmpty();
    }

    @Test
    void aCompensationStepNeedsNoWayInOfItsOwn() throws Exception {
        // It is declared on the step it undoes and started by the rollback pipeline. Requiring a
        // precondition here is what produced the anchor-with-a-false-guard, and an anchor written
        // without the guard is a compensation wired into the happy path.
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/compensation.json")).isEmpty();
    }

    @Test
    void aStepNothingCanStartIsReported() throws Exception {
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/unreachable-step.json"))
                .anySatisfy(v -> assertThat(v).contains("'orphan'", "nothing would ever start it"));
    }

    @Test
    void duplicateStepIdIsReported() throws Exception {
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/duplicate-id.json"))
                .anySatisfy(v -> assertThat(v).contains("Duplicate step id 's1'"));
    }

    @Test
    void danglingReferenceAndBadCronAreReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/dangling-and-cron.yaml");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("unknown precondition step 'nope'"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("not a valid cron expression"));
    }

    @Test
    void badJexlAndSchemaViolationBothReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/bad-jexl-and-schema.json");
        // schema: status not in the allowed enum
        assertThat(violations).anySatisfy(v -> assertThat(v).containsIgnoringCase("status"));
        // semantic: unparseable precondition expression
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("invalid JEXL expression"));
    }

    @Test
    void badCorrelationExpressionJexlIsReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/bad-correlation-jexl.json");
        assertThat(violations).anySatisfy(v -> assertThat(v)
                .contains("correlationExpression")
                .contains("invalid JEXL expression"));
        // The syntactically valid SEND_MESSAGE correlationExpression is not flagged.
        assertThat(violations).noneSatisfy(v -> assertThat(v).contains("step 'send' correlationExpression"));
    }

    @Test
    void formMissingFieldsIsReported() throws Exception {
        assertThat(validate(SpecValidator.Kind.FORM, "/invalid/forms/missing-fields.json"))
                .anySatisfy(v -> assertThat(v).containsIgnoringCase("fields"));
    }

    @Test
    void decisionTableArityIsReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.RULE, "/invalid/rules/bad-arity.json");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("'when' must have 2 cells"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("'then' must have 1 cells"));
    }

    @Test
    void ruleBadJexlIsReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.RULE, "/invalid/rules/bad-jexl.yaml");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("invalid JEXL expression"));
    }

    @Test
    void preconditionsDeclaredAsLinksAreSeenByTheStructuralChecks() throws Exception {
        // Read only preconditionStepIds/preconditionStepId and every one of these steps looks like
        // it waits for nothing, which the roots rule reports as a step nothing would ever start.
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/link-preconditions.json")).isEmpty();
    }

    @Test
    void anUnparseableConditionOnALinkIsAViolation() throws Exception {
        JsonNode document = new ObjectMapper().readTree("""
                {
                  "id": "bad-link-jexl", "name": "Bad link JEXL", "version": 1,
                  "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "next", "type": "ACTION", "name": "Next", "topic": "work",
                      "preconditions": [ { "stepId": "start", "expression": "amount >" } ] }
                  ]
                }
                """);

        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, document))
                .anySatisfy(violation -> assertThat(violation)
                        .contains("step 'next' precondition on 'start'")
                        .contains("invalid JEXL expression"));
    }
}
