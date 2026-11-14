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

    @Test
    void retentionIsMetadataAndMintsNoVersion() throws Exception {
        var def = def(TWO_STEPS);
        assertThat(service.contentHash(def.withRetention(io.mateu.workflow.domain.aggregates.ProcessRetention.of("P30D"))))
                .isEqualTo(service.contentHash(def));
        assertThat(service.contentHash(def.withRetention(io.mateu.workflow.domain.aggregates.ProcessRetention.perStatus(
                java.util.Map.of("error", "P180D"))))).isEqualTo(service.contentHash(def));
    }

    @Test
    void searchAttributesArePartOfTheVersion() throws Exception {
        var def = def(TWO_STEPS);
        var indexed = def.withSearchAttributes(java.util.Map.of("customerId", "${customerId}"));
        assertThat(service.contentHash(indexed)).isNotEqualTo(service.contentHash(def));
        assertThat(service.contentHash(indexed.withSearchAttributes(java.util.Map.of("customerId",
                java.util.Map.of("value", "${customerId}", "type", "text")))))
                .isNotEqualTo(service.contentHash(indexed));
        // Absent hashes as before the field existed: an upgrade mints nothing.
        assertThat(service.contentHash(def.withSearchAttributes(null))).isEqualTo(service.contentHash(def));
    }

    /** What the classpath import saves: every step stamped with the definition's id. */
    private static WorkflowDefinition asClasspathImportsIt(WorkflowDefinition d) {
        return new WorkflowDefinition(d.id(), d.name(), d.version(), d.description(),
                d.limitConcurrentExecutions(), d.maxConcurrentExecutions(), d.enqueueOnLimit(),
                d.cronExpression(), d.defaultMaxStepExecutions(),
                d.steps().stream().map(s -> s.withWorkflowDefinitionId(d.id())).toList());
    }

    @Test
    void theSameContentImportedFromGitAfterTheClasspathRecordsNothing() throws Exception {
        // The classpath import stamps the steps, the directory / Git import does not: the same file
        // used to hash twice, and the Git import recorded v2 with identical content.
        var fromClasspath = asClasspathImportsIt(def(TWO_STEPS));
        var fromGit = def(TWO_STEPS);
        assertThat(service.contentHash(fromGit)).isEqualTo(service.contentHash(fromClasspath));

        var head = new WorkflowDefinitionVersionEntity(
                "d1:1", "d1", 1, "Demo", "{}", service.contentHash(fromClasspath), null);
        when(versions.findTopByDefinitionIdOrderByVersionDesc("d1")).thenReturn(Optional.of(head));

        assertThat(service.reconcile(fromGit)).isEqualTo(1);
        verify(versions, never()).save(any());
    }

    @Test
    void aHeadHashedBeforeTheNormalizationStillMatchesUnchangedContent() throws Exception {
        // Recorded by an older engine from a Git import: the hash of the unstamped steps.
        var fromGit = def(TWO_STEPS);
        var head = new WorkflowDefinitionVersionEntity(
                "d1:1", "d1", 1, "Demo", "{}", service.legacyContentHash(fromGit), null);
        when(versions.findTopByDefinitionIdOrderByVersionDesc("d1")).thenReturn(Optional.of(head));

        assertThat(service.reconcile(fromGit)).isEqualTo(1);
        verify(versions, never()).save(any());
    }
}
