package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a definition's {@code updates} must be to load — checked with the definition, never at the first call. */
class UpdateDefinitionRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static final String STEPS = """
            "steps": [
              {"id": "start", "type": "START", "name": "Start"},
              {"id": "work", "type": "ACTION", "name": "Work", "topic": "work", "preconditionStepId": "start"},
              {"id": "wait", "type": "WAIT_FOR_MESSAGE", "name": "Wait", "messageName": "go",
               "correlationExpression": "businessKey", "preconditionStepId": "work"},
              {"id": "end", "type": "END", "name": "End", "preconditionStepId": "wait"}
            ]""";

    private static WorkflowDefinition definition(String updates) throws Exception {
        return JSON.readValue("{\"id\": \"orders\", \"name\": \"Orders\", " + STEPS + ", \"updates\": " + updates + "}",
                WorkflowDefinition.class);
    }

    @Test
    void aWellFormedUpdateLoadsAndPassesTheSchema() throws Exception {
        var definition = definition("""
                [{"name": "changeAddress",
                  "accepts": {"type": "object", "required": ["address"], "properties": {"address": {"type": "string"}}},
                  "acceptedWhile": ["wait"],
                  "validate": [{"expression": "payload.address != address", "message": "same"}],
                  "set": {"address": "payload.address"},
                  "completes": "wait",
                  "reply": "{'address': address}",
                  "sensitive": ["card"]}]""");

        definition.checkInvariants();
        new WorkflowDefinitionValidator().validate(definition);
        assertThat(definition.update("changeAddress")).isPresent();
        assertThat(definition.update("other")).isEmpty();
        assertThat(definition.updatesOrEmpty()).hasSize(1);
        assertThat(definition.update("changeAddress").get().windowSteps()).containsExactly("wait");
    }

    @Test
    void namesMustBeWordsAndUnique() throws Exception {
        assertThatThrownBy(() -> definition("[{\"name\": \"change address\"}]").checkInvariants())
                .hasMessageContaining("name must be a word");
        assertThatThrownBy(() -> definition("[{\"name\": \"a\"}, {\"name\": \"a\"}]").checkInvariants())
                .hasMessageContaining("declared twice");
    }

    @Test
    void stepsNamedMustExistAndCompletesMustBeAMessageWait() throws Exception {
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"acceptedWhile\": [\"nope\"]}]").checkInvariants())
                .hasMessageContaining("acceptedWhile names a step that does not exist: 'nope'");
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"completes\": \"nope\"}]").checkInvariants())
                .hasMessageContaining("completes names a step that does not exist");
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"completes\": \"work\"}]").checkInvariants())
                .hasMessageContaining("must name a WAIT_FOR_MESSAGE step");
    }

    @Test
    void expressionsMustParseAndTheSchemaMustBeASchema() throws Exception {
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"set\": {\"x\": \"(1 +\"}}]").checkInvariants())
                .hasMessageContaining("set.x: the expression does not parse");
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"validate\": [{\"expression\": \"\"}]}]").checkInvariants())
                .hasMessageContaining("validate[0] needs an expression");
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"reply\": \"a ==\"}]").checkInvariants())
                .hasMessageContaining("reply: the expression does not parse");
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"accepts\": {\"type\": 12}}]").checkInvariants())
                .hasMessageContaining("accepts is not a valid JSON Schema");
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"reply\": \"1\", \"replyTemplate\": {\"x\": 1}}]")
                .checkInvariants()).hasMessageContaining("both reply and replyTemplate");
        assertThatThrownBy(() -> definition("[{\"name\": \"a\", \"set\": {\"x\": \" \"}}]").checkInvariants())
                .hasMessageContaining("set.x needs an expression");
    }

    @Test
    void aDefinitionWithoutUpdatesIsUnchanged() throws Exception {
        var definition = JSON.readValue("{\"id\": \"orders\", \"name\": \"Orders\", " + STEPS + "}", WorkflowDefinition.class);

        definition.checkInvariants();
        assertThat(definition.updates()).isNull();
        assertThat(definition.updatesOrEmpty()).isEmpty();
        assertThat(JSON.writeValueAsString(definition)).doesNotContain("\"updates\"");
    }
}
