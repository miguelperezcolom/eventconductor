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
    void validTaskContractPasses() throws Exception {
        assertThat(validate(SpecValidator.Kind.TASK, "/valid/tasks/greet.json")).isEmpty();
    }

    @Test
    void aTaskWithABadTypeAndANonConstantErrorCodeFails() throws Exception {
        var violations = validate(SpecValidator.Kind.TASK, "/invalid/tasks/bad-type-and-code.json");
        // An unknown attribute type and an error code that is not a Java identifier are both rejected.
        assertThat(violations).isNotEmpty();
        assertThat(String.join("\n", violations)).contains("amount").contains("code");
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

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    private static final String SYNC_BOOKING = """
            {"id": "sync", "name": "Sync", "version": 1,
             "syncInvocation": {"enabled": true, "onFailure": "REPLY_AFTER_COMPENSATION"},
             "steps": [
               {"id": "start", "type": "START", "name": "Start"},
               {"id": "choice", "type": "CHOICE", "name": "Choice", "preconditionStepId": "start"},
               {"id": "ok", "type": "REPLY", "name": "Ok", "replyVariables": ["bookingId"], "preconditionStepId": "choice"},
               {"id": "ko", "type": "REPLY", "name": "Ko", "replyExpression": "{'ok': false}", "preconditionStepId": "choice"},
               {"id": "end", "type": "END", "name": "End", "preconditionStepId": "ok"},
               {"id": "silent", "type": "END", "name": "Silent", "preconditionStepId": "choice"}
             ]}
            """;

    @Test
    void replyStepsOnDifferentChoiceBranchesPassWithAWarningForASilentEnd() throws Exception {
        var doc = json(SYNC_BOOKING);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc)).isEmpty();
        assertThat(validator.warnings(SpecValidator.Kind.WORKFLOW, doc)).anyMatch(w -> w.contains("silent"));
    }

    @Test
    void twoRepliesOnOnePathFailTheBuild() throws Exception {
        var doc = json("""
                {"id": "twice", "name": "Twice", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "r1", "type": "REPLY", "name": "R1", "preconditionStepId": "start"},
                  {"id": "r2", "type": "REPLY", "name": "R2", "preconditionStepId": "r1"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc)).anyMatch(v -> v.contains("same path"));
    }

    @Test
    void aSyncInvocableWorkflowWithoutAReplyFailsTheBuild() throws Exception {
        var doc = json("""
                {"id": "mute", "name": "Mute", "version": 1, "syncInvocation": {"enabled": true}, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "end", "type": "END", "name": "End", "preconditionStepId": "start"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc)).anyMatch(v -> v.contains("no REPLY step"));
    }

    @Test
    void aReplyWithBothSourcesOrABadExpressionFails() throws Exception {
        var both = json("""
                {"id": "both", "name": "Both", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "r", "type": "REPLY", "name": "R", "preconditionStepId": "start",
                   "replyVariables": ["a"], "replyExpression": "b"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, both)).isNotEmpty();
        var bad = json("""
                {"id": "bad", "name": "Bad", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "r", "type": "REPLY", "name": "R", "preconditionStepId": "start", "replyExpression": "{{{"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, bad)).anyMatch(v -> v.contains("replyExpression"));
    }

    @Test
    void aReplyTemplateWithABrokenExpressionFailsTheBuild() throws Exception {
        var doc = json("""
                {"id": "t", "name": "T", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "r", "type": "REPLY", "name": "R", "preconditionStepId": "start",
                   "replyTemplate": {"ok": "${a}", "bad": ["${b +}"], "open": "x ${y"}}
                ]}
                """);
        var violations = validator.validate(SpecValidator.Kind.WORKFLOW, doc);
        assertThat(violations).anyMatch(v -> v.contains("replyTemplate") && (v.contains("bad") || v.contains("Unterminated")));
    }

    @Test
    void aPublishEventNeedsItsEventBlockAndValidTemplates() throws Exception {
        var missing = json("""
                {"id": "p", "name": "P", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "pub", "type": "PUBLISH_EVENT", "name": "Pub", "preconditionStepId": "start"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, missing)).isNotEmpty();
        var bad = json("""
                {"id": "p", "name": "P", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "pub", "type": "PUBLISH_EVENT", "name": "Pub", "preconditionStepId": "start",
                   "event": {"destination": "d", "type": "t", "payload": {"x": "${a +}"}}}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, bad)).anyMatch(v -> v.contains("event.payload"));
        var good = json("""
                {"id": "p", "name": "P", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "pub", "type": "PUBLISH_EVENT", "name": "Pub", "preconditionStepId": "start",
                   "event": {"destination": "d", "type": "t", "key": "${k}", "payload": {"x": "${a}"}}}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).isEmpty();
    }
}
