package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
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
    void aFileWithTheOlderBooleansDoesNotParseOnItsOwn() throws Exception {
        // `disabled` and `archived` are no longer fields of the definition: one `status` says what
        // they said. A file still using them has to be adopted, or it does not read at all.
        var node = fileWith("\"disabled\": true,");

        assertThatThrownBy(() -> mapper.treeToValue(node, WorkflowDefinition.class))
                .hasMessageContaining("disabled");
    }

    @Test
    void theLegacyDisabledBooleanIsReadAsTheStatus() throws Exception {
        var node = fileWith("\"disabled\": true,");

        ImportWorkflowDefinitionsFromDirectoryUseCase.adoptLegacyLifecycleFields(node, "order.ec");

        var definition = mapper.treeToValue(node, WorkflowDefinition.class);
        assertThat(definition.declaredStatus()).isEqualTo(WorkflowStatus.DISABLED);
    }

    @Test
    void theLegacyArchivedBooleanIsReadAsTheStatus() throws Exception {
        var node = fileWith("\"archived\": true,");

        ImportWorkflowDefinitionsFromDirectoryUseCase.adoptLegacyLifecycleFields(node, "order.ec");

        assertThat(mapper.treeToValue(node, WorkflowDefinition.class).declaredStatus())
                .isEqualTo(WorkflowStatus.ARCHIVED);
    }

    @Test
    void draftAndActiveMeantNothingAndAreDropped() throws Exception {
        for (var status : new String[]{"DRAFT", "ACTIVE"}) {
            var node = fileWith("\"status\": \"%s\",".formatted(status));

            ImportWorkflowDefinitionsFromDirectoryUseCase.adoptLegacyLifecycleFields(node, "order.ec");

            var definition = mapper.treeToValue(node, WorkflowDefinition.class);
            assertThat(definition.declaredStatus()).as(status).isEqualTo(WorkflowStatus.ACTIVE);
        }
    }

    @Test
    void aFileWithoutOneIsLeftAlone() throws Exception {
        var node = fileWith("");

        ImportWorkflowDefinitionsFromDirectoryUseCase.adoptLegacyLifecycleFields(node, "order.ec");

        var definition = mapper.treeToValue(node, WorkflowDefinition.class);
        assertThat(definition.declaredStatus()).isEqualTo(WorkflowStatus.ACTIVE);
    }
}
