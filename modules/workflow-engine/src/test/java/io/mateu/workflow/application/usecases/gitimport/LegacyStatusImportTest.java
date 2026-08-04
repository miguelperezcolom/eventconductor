package io.mateu.workflow.application.usecases.gitimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Definition files carrying a `status` field, which the graph editor used to write.
 *
 * <p>The engine has never had such a property: it is not in the schema, and the import parses with
 * a mapper that rejects what it does not recognise. So a file written with the editor's old status
 * dropdown did not fail validation with a message an author could act on — it failed to parse, and
 * the import logged it as a skipped file. Anyone who used that dropdown to keep a workflow out of
 * service ended up with a definition the engine would not read at all.
 */
class LegacyStatusImportTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private com.fasterxml.jackson.databind.JsonNode fileWith(String statusLine) throws Exception {
        return mapper.readTree("""
                {
                  "id": "order",
                  "name": "Order",
                  %s
                  "steps": []
                }
                """.formatted(statusLine));
    }

    @Test
    void aFileWithTheLegacyStatusDoesNotEvenParse() throws Exception {
        // What the editor produced, before this: unreadable.
        var node = fileWith("\"status\": \"DISABLED\",");

        assertThatThrownBy(() -> mapper.treeToValue(node, WorkflowDefinition.class))
                .hasMessageContaining("status");
    }

    @Test
    void theLegacyDisabledStatusIsReadAsTheDisabledFlag() throws Exception {
        var node = fileWith("\"status\": \"DISABLED\",");

        ImportWorkflowDefinitionsFromGitUseCase.adoptLegacyStatus(node, "order.ec");

        var definition = mapper.treeToValue(node, WorkflowDefinition.class);
        assertThat(definition.disabled()).isTrue();
    }

    @Test
    void theLegacyArchivedStatusIsReadAsTheArchivedFlag() throws Exception {
        var node = fileWith("\"status\": \"ARCHIVED\",");

        ImportWorkflowDefinitionsFromGitUseCase.adoptLegacyStatus(node, "order.ec");

        assertThat(mapper.treeToValue(node, WorkflowDefinition.class).archived()).isTrue();
    }

    @Test
    void draftAndActiveMeantNothingAndAreDropped() throws Exception {
        for (var status : new String[]{"DRAFT", "ACTIVE"}) {
            var node = fileWith("\"status\": \"%s\",".formatted(status));

            ImportWorkflowDefinitionsFromGitUseCase.adoptLegacyStatus(node, "order.ec");

            var definition = mapper.treeToValue(node, WorkflowDefinition.class);
            assertThat(definition.disabled()).as(status).isFalse();
            assertThat(definition.archived()).as(status).isFalse();
        }
    }

    @Test
    void aFileWithoutOneIsLeftAlone() throws Exception {
        var node = fileWith("");

        ImportWorkflowDefinitionsFromGitUseCase.adoptLegacyStatus(node, "order.ec");

        var definition = mapper.treeToValue(node, WorkflowDefinition.class);
        assertThat(definition.disabled()).isFalse();
    }
}
