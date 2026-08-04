package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntity;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntityRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDefinitionVersioningServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final WorkflowDefinitionVersionEntityRepository versions =
            mock(WorkflowDefinitionVersionEntityRepository.class);
    private final WorkflowDefinitionVersioningService service =
            new WorkflowDefinitionVersioningService(versions);

    private WorkflowDefinition def(String stepsJson) throws Exception {
        return objectMapper.readValue(
                "{\"id\":\"d1\",\"name\":\"Demo\",\"version\":0,\"steps\":" + stepsJson + "}",
                WorkflowDefinition.class);
    }

    private static final String TWO_STEPS =
            "[{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
                    + "{\"id\":\"charge\",\"type\":\"ACTION\",\"name\":\"Charge\"}]";
    private static final String TWO_STEPS_REORDERED =
            "[{\"id\":\"charge\",\"type\":\"ACTION\",\"name\":\"Charge\"},"
                    + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"}]";

    @Test
    void recordsVersion1WhenNoHistoryExists() throws Exception {
        when(versions.findTopByDefinitionIdOrderByVersionDesc("d1")).thenReturn(Optional.empty());

        int v = service.reconcile(def(TWO_STEPS));

        assertThat(v).isEqualTo(1);
        verify(versions).save(any(WorkflowDefinitionVersionEntity.class));
    }

    @Test
    void isIdempotentWhenContentIsUnchanged() throws Exception {
        var def = def(TWO_STEPS);
        var latest = new WorkflowDefinitionVersionEntity(
                "d1:1", "d1", 1, "Demo", "{}", service.contentHash(def), null);
        when(versions.findTopByDefinitionIdOrderByVersionDesc("d1")).thenReturn(Optional.of(latest));

        int v = service.reconcile(def);

        assertThat(v).isEqualTo(1);
        verify(versions, never()).save(any());
    }

    @Test
    void incrementsWhenContentChanges() throws Exception {
        var previous = def(TWO_STEPS);
        var latest = new WorkflowDefinitionVersionEntity(
                "d1:1", "d1", 1, "Demo", "{}", service.contentHash(previous), null);
        when(versions.findTopByDefinitionIdOrderByVersionDesc("d1")).thenReturn(Optional.of(latest));

        var changed = def("[{\"id\":\"start\",\"type\":\"START\",\"name\":\"Renamed start\"}]");
        int v = service.reconcile(changed);

        assertThat(v).isEqualTo(2);
        verify(versions).save(any(WorkflowDefinitionVersionEntity.class));
    }

    @Test
    void contentHashIsStableUnderStepReordering() throws Exception {
        assertThat(service.contentHash(def(TWO_STEPS)))
                .isEqualTo(service.contentHash(def(TWO_STEPS_REORDERED)));
    }

    @Test
    void runtimeFlagsDoNotChangeContentHash() throws Exception {
        var def = def(TWO_STEPS);
        assertThat(service.contentHash(def.withPaused(true))).isEqualTo(service.contentHash(def));
        assertThat(service.contentHash(def.withDisabled(true))).isEqualTo(service.contentHash(def));
        assertThat(service.contentHash(def.withArchived(true))).isEqualTo(service.contentHash(def));
        assertThat(service.contentHash(def.withVersion(99))).isEqualTo(service.contentHash(def));
    }
}
