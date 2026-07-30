package io.mateu.workflow.application.usecases.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportWorkflowDefinitionToYamlUseCaseTest {

    final WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
    final ExportWorkflowDefinitionToYamlUseCase useCase =
            new ExportWorkflowDefinitionToYamlUseCase(repository);

    @Test
    void exportedYamlRoundTripsThroughTheImporterParsePath() throws Exception {
        var definition = definition();
        when(repository.findById("wf-1")).thenReturn(Optional.of(definition));

        var export = useCase.handle("wf-1");

        // Same parse path as ImportWorkflowDefinitionsFromGitUseCase.importDefinitionFile.
        var node = new YAMLMapper().readTree(export.content());
        assertThat(node.has("name")).isTrue();
        assertThat(node.has("steps")).isTrue();
        var reimported = new ObjectMapper().findAndRegisterModules()
                .treeToValue(node, WorkflowDefinition.class);

        assertThat(reimported).isEqualTo(definition);
    }

    @Test
    void fileNameIsASlugOfTheNamePlusVersion() {
        when(repository.findById("wf-1")).thenReturn(Optional.of(definition()));

        assertThat(useCase.handle("wf-1").fileName()).isEqualTo("order-fulfillment-v3.yaml");
    }

    @Test
    void unknownDefinitionThrows() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle("missing"))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static WorkflowDefinition definition() {
        var step = new Step(
                "reserve", "wf-1", StepType.ACTION, "Reserve stock", "Reserves the stock",
                null, null, null, false, "inventory", null, null, null, null,
                0, null, null, null, null, 30000, 3, true, null, 0);
        return new WorkflowDefinition(
                "wf-1", "Order fulfillment!", 3, "Ships orders",
                WorkflowDefinitionStatus.ACTIVE, null,
                true, 5, true, null, 0, List.of(step));
    }
}
