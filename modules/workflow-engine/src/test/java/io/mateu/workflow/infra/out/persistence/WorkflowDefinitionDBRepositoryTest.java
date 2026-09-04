package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.application.services.WorkflowDefinitionVersioningService;
import io.mateu.workflow.domain.aggregates.NodePosition;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The diagram arrangement's trip through JPA.
 *
 * <p>The validator here is the real one, not a mock, and that is the point of these tests as much
 * as the column is: {@code save} re-serialises the record and validates it against the schema, whose
 * root is {@code additionalProperties: false}. A component the schema does not declare does not fail
 * quietly here — it fails every save the engine ever makes.
 */
class WorkflowDefinitionDBRepositoryTest {

    private final WorkflowDefinitionEntityRepository entities =
            mock(WorkflowDefinitionEntityRepository.class);
    private final WorkflowDefinitionVersioningService versioning =
            mock(WorkflowDefinitionVersioningService.class);
    private final WorkflowDefinitionDBRepository repository =
            new WorkflowDefinitionDBRepository(entities, new WorkflowDefinitionValidator(), versioning);

    private WorkflowDefinition definition() {
        var step = new Step("start", "wf-1", StepType.START, "Start", null, null, null, null, false,
                null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        return new WorkflowDefinition("wf-1", "Order", 1, null, false, 0, false, null, 0,
                List.of(step));
    }

    private WorkflowDefinitionEntity savedEntity(WorkflowDefinition definition) {
        when(versioning.reconcile(any())).thenReturn(1);
        repository.save(definition);
        var captor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(entities).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void anArrangementSurvivesTheRoundTripThroughTheColumn() {
        var arranged = definition().withLayout(Map.of("start", new NodePosition(60, 160)));

        var entity = savedEntity(arranged);
        // What the column holds, not how it is spaced — the serializer pretty-prints.
        assertThat(entity.getLayoutJson()).contains("start").contains("60").contains("160");

        when(entities.findById("wf-1")).thenReturn(Optional.of(entity));
        assertThat(repository.findById("wf-1")).hasValueSatisfying(loaded ->
                assertThat(loaded.layout()).containsExactly(
                        org.assertj.core.api.Assertions.entry("start", new NodePosition(60, 160))));
    }

    /** A workflow nobody arranged leaves the column null, which is what old rows already read as. */
    @Test
    void anUnarrangedDefinitionLeavesTheColumnEmpty() {
        var entity = savedEntity(definition());

        assertThat(entity.getLayoutJson()).isNull();

        when(entities.findById("wf-1")).thenReturn(Optional.of(entity));
        assertThat(repository.findById("wf-1")).hasValueSatisfying(loaded ->
                assertThat(loaded.layout()).isNull());
    }

    /**
     * A layout that cannot be read costs the layout, not the definition. Coordinates are
     * presentation: a row whose JSON is somehow unreadable should give you a graph that lays itself
     * out, not a workflow the engine refuses to load.
     */
    @Test
    void anUnreadableArrangementLoadsAsNoArrangement() {
        var entity = savedEntity(definition());
        entity.setLayoutJson("{ this is not json");

        when(entities.findById("wf-1")).thenReturn(Optional.of(entity));

        assertThat(repository.findById("wf-1")).hasValueSatisfying(loaded -> {
            assertThat(loaded.layout()).isNull();
            assertThat(loaded.name()).isEqualTo("Order");
        });
    }
}
