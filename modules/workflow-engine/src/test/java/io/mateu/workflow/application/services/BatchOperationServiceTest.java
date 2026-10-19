package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessListingFilter;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessSummaryPage;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.mateu.workflow.infra.config.BatchOperationProperties;
import io.mateu.workflow.infra.out.memory.InMemoryBatchOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The batch operation's life in memory: created over a filter (never an empty one), walked a page at a
 * time by id, each process one ordinary command whose refusals are counted by reason, cancelled between
 * two processes, resumed by a fresh service, and reported to the metrics.
 */
class BatchOperationServiceTest {

    static final BatchOperationFilter ERRORS = new BatchOperationFilter("orders", null, Set.of(ProcessStatus.ERROR),
            null, null, null, null, false, "customerId = C-9");

    final InMemoryBatchOperationRepository repository = new InMemoryBatchOperationRepository();
    final ProcessRepository processes = mock(ProcessRepository.class);
    final ProcessOperationsService operations = mock(ProcessOperationsService.class);
    final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    final BatchOperationProperties properties = new BatchOperationProperties();
    final List<String> ids = IntStream.range(0, 25).mapToObj(i -> String.format("p%02d", i)).toList();

    BatchOperationService service() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("metrics", metrics);
        var listing = new ProcessListingService(processes, new StaticListableBeanFactory().getBeanProvider(
                io.mateu.workflow.application.out.ProcessIndexRepository.class), new ProcessStatusAnnouncer(false, ""),
                new StaticListableBeanFactory().getBeanProvider(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class));
        return new BatchOperationService(repository, processes, listing, operations, properties,
                beans.getBeanProvider(WorkflowMetrics.class));
    }

    @BeforeEach
    void processes() {
        properties.setPageSize(10);
        properties.setMaxPerSecond(0);
        when(processes.searchSummaries(any(), anyInt(), anyInt()))
                .thenReturn(new ProcessSummaryPage(List.of(), ids.size(), 0, 1));
        when(processes.findIdsMatching(any(), any(), anyInt())).thenAnswer(call -> {
            String after = call.getArgument(1);
            int limit = call.getArgument(2);
            return ids.stream().filter(id -> after == null || id.compareTo(after) > 0).limit(limit).toList();
        });
    }

    @Test
    void walksEveryProcessPageByPageCountingEachOutcome() {
        doThrow(new ProcessOperationsService.ProcessStateConflictException("p03", "cancel", ProcessStatus.COMPLETED, null))
                .when(operations).cancel(eq("p03"), any());
        doThrow(new ProcessOperationsService.ProcessNotFoundException("gone")).when(operations).cancel(eq("p04"), any());
        doThrow(io.mateu.workflow.security.FlowAuthorizationDeniedException.of("cancel", "ana", List.of("x"), List.of()))
                .when(operations).cancel(eq("p05"), any());
        doThrow(new IllegalStateException()).when(operations).cancel(eq("p06"), any());
        var service = service();
        var batch = service.create(BatchOperationType.CANCEL, ERRORS, null, null);
        assertThat(batch.status()).isEqualTo(BatchOperationStatus.CREATED);
        assertThat(batch.matched()).isEqualTo(25);
        assertThat(batch.filter().query()).isEqualTo("customerId = C-9");

        // The filter reaches the store whole: definition, status and the parsed attribute condition.
        assertThat(service.runNextPage()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(ProcessListingFilter.class);
        verify(processes).findIdsMatching(captor.capture(), eq(null), eq(10));
        assertThat(captor.getValue().workflowDefinitionId()).isEqualTo("orders");
        assertThat(captor.getValue().normalisedSearchAttributes()).hasSize(1);
        assertThat(repository.findById(batch.id()).orElseThrow().cursor()).isEqualTo("p09");

        while (service.runNextPage()) {
            // to the end
        }
        var done = repository.findById(batch.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(BatchOperationStatus.COMPLETED);
        assertThat(done.succeeded()).isEqualTo(21);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(done.errors()).containsExactly("p06: IllegalStateException");
        assertThat(done.skippedByReason()).containsEntry(BatchOperation.SKIPPED_NOT_APPLICABLE, 1L)
                .containsEntry(BatchOperation.SKIPPED_GONE, 1L).containsEntry(BatchOperation.SKIPPED_NOT_AUTHORIZED, 1L);
        assertThat(done.percent()).isEqualTo(100);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(service.runNextPage()).isFalse();
        verify(metrics).batchOperationFinished("CANCEL", "completed");
        verify(metrics).batchOperationItems("CANCEL", "failed", 1);
    }

    @Test
    void everyTypeIsItsOwnCommand() {
        var service = service();
        for (var type : BatchOperationType.values()) {
            service.create(type, ERRORS, io.mateu.workflow.domain.batch.BatchOperationParameters.of(Map.of("approved", "yes"),
                    List.of("tmp"), "charge", "why"), null);
        }
        while (service.runNextPage()) {
            // all of them
        }
        verify(operations, org.mockito.Mockito.times(25)).cancel(anyString(), any());
        verify(operations, org.mockito.Mockito.times(25)).retry(anyString(), any());
        verify(operations, org.mockito.Mockito.times(25)).restart(anyString(), any());
        verify(operations, org.mockito.Mockito.times(25)).pause(anyString(), any());
        verify(operations, org.mockito.Mockito.times(25)).resume(anyString(), any());
        var reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.times(25)).setVariables(anyString(),
                eq(List.of(new io.mateu.workflow.domain.aggregates.Variable("approved", "yes"))), eq(List.of("tmp")),
                reason.capture(), any());
        assertThat(reason.getValue()).startsWith("why (batch ");
        verify(operations, org.mockito.Mockito.times(25)).retryFromStep(anyString(), eq("charge"), anyString(), any());
        verify(operations, org.mockito.Mockito.times(25)).migrate(anyString(), isNull(), eq(Map.of()), eq(Map.of()),
                anyString(), any());
        assertThat(service.list()).hasSize(8).allMatch(op -> op.status() == BatchOperationStatus.COMPLETED);
    }

    @Test
    void aCancellationLandsBetweenTwoProcessesAndWins() {
        var service = service();
        var batch = service.create(BatchOperationType.RETRY, ERRORS, null, null);
        properties.setPageSize(25);
        var seen = new ArrayList<String>();
        doAnswer(call -> {
            seen.add(call.getArgument(0));
            if (seen.size() == 3) {
                service.cancel(batch.id());
            }
            return null;
        }).when(operations).retry(anyString(), any());

        assertThat(service.runNextPage()).isTrue();

        var stopped = repository.findById(batch.id()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(BatchOperationStatus.CANCELLED);
        assertThat(seen).hasSize(BatchOperationService.CANCEL_CHECK_EVERY);
        assertThat(stopped.succeeded()).isEqualTo(BatchOperationService.CANCEL_CHECK_EVERY);
        assertThat(service.runNextPage()).isFalse();
        verify(metrics).batchOperationFinished("RETRY", "cancelled");
        assertThatThrownBy(() -> service.cancel(batch.id()))
                .isInstanceOf(BatchOperationService.BatchOperationNotActiveException.class);
        assertThatThrownBy(() -> service.cancel("nope"))
                .isInstanceOf(BatchOperationService.BatchOperationNotFoundException.class);
    }

    @Test
    void aFreshServiceResumesFromTheCursor() {
        var batch = service().create(BatchOperationType.PAUSE, ERRORS, null, null);
        service().runNextPage();
        service().runNextPage();
        assertThat(repository.findById(batch.id()).orElseThrow().cursor()).isEqualTo("p19");
        service().runNextPage();
        verify(operations, org.mockito.Mockito.times(25)).pause(anyString(), any());
        assertThat(repository.findById(batch.id()).orElseThrow().status()).isEqualTo(BatchOperationStatus.COMPLETED);
    }

    @Test
    void aFilterThatNoLongerReadsFailsTheBatch() {
        var broken = BatchOperation.create("b", BatchOperationType.CANCEL, new BatchOperationFilter(null, null, null,
                null, null, null, null, true, "amount >"), null, 1, null, LocalDateTime.now());
        repository.create(broken);
        service().runNextPage();
        var failed = repository.findById("b").orElseThrow();
        assertThat(failed.status()).isEqualTo(BatchOperationStatus.FAILED);
        assertThat(failed.failure()).contains("The filter no longer reads");
        verify(operations, never()).cancel(anyString(), any());
    }

    @Test
    void refusesWhatIsNotABatch() {
        var service = service();
        assertThatThrownBy(() -> service.create(null, ERRORS, null, null)).hasMessageContaining("needs a type");
        assertThatThrownBy(() -> service.create(BatchOperationType.CANCEL, null, null, null)).hasMessageContaining("needs a filter");
        assertThatThrownBy(() -> service.create(BatchOperationType.CANCEL, new BatchOperationFilter(null, " ", null, null,
                null, null, null, false, ""), null, null)).hasMessageContaining("never acts on every process");
        assertThatThrownBy(() -> service.create(BatchOperationType.CANCEL, new BatchOperationFilter(null, null, null, null,
                null, null, null, false, "amount > lots"), null, null)).hasMessageContaining("numbers and dates");
        assertThat(service.get("none")).isEmpty();
    }

    @Test
    void theThrottleSpacesTheCommands() {
        properties.setMaxPerSecond(100);
        var service = service();
        service.create(BatchOperationType.CANCEL, ERRORS, null, null);
        var started = System.nanoTime();
        service.runNextPage();
        // Ten commands at 100 a second: at least nine gaps of 10 ms.
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(85);
    }

    @Test
    void theFilterDescribesItselfAndTheProgressIsAPercentage() {
        var filter = new BatchOperationFilter("orders", "billing", Set.of(ProcessStatus.ERROR, ProcessStatus.CANCELLED),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0), "bk", "text", true, "a = 1");
        assertThat(filter.describe()).contains("definition orders", "tag billing", "[CANCELLED, ERROR]", "only errors",
                "business key ~ bk", "text ~ text", "created ≥", "created ≤", "a = 1");
        assertThat(new BatchOperationFilter(null, null, null, null, null, null, null, false, null).describe()).isEqualTo("—");
        var op = BatchOperation.create("x", BatchOperationType.CANCEL, filter, Map.of(), 4, null, LocalDateTime.now());
        assertThat(op.percent()).isZero();
        assertThat(op.createdBy()).isNull();
        assertThat(op.progressed("p", 1, 1, Map.of(), List.of(), LocalDateTime.now()).percent()).isEqualTo(50);
        var manyErrors = IntStream.range(0, 30).mapToObj(i -> "e" + i).toList();
        assertThat(op.progressed("p", 0, 30, Map.of(), manyErrors, LocalDateTime.now()).errors())
                .hasSize(BatchOperation.MAX_ERRORS);
        assertThat(BatchOperation.create("y", BatchOperationType.CANCEL, filter, null, 0, null, LocalDateTime.now()).percent()).isZero();
    }

    @Test
    void migrateMovesEachProcessOfTheVersionWithTheSameMapping() {
        properties.setPageSize(30);
        var onV2 = io.mateu.workflow.domain.aggregates.Process.builder().id("p01").workflowDefinitionVersion(2)
                .status(ProcessStatus.RUNNING).build();
        var onV1 = io.mateu.workflow.domain.aggregates.Process.builder().id("p00").workflowDefinitionVersion(1)
                .status(ProcessStatus.RUNNING).build();
        when(processes.findById("p00")).thenReturn(java.util.Optional.of(onV1));
        when(processes.findById("p01")).thenReturn(java.util.Optional.of(onV2));
        var parameters = io.mateu.workflow.domain.batch.BatchOperationParameters.migrate(3, 1, Map.of("b", "b2"),
                Map.of("x", "1"), "fleet fix");
        assertThat(parameters).containsEntry("targetVersion", "3").containsEntry("fromVersion", "1")
                .containsEntry("map.b", "b2").containsEntry("var.x", "1").containsEntry("reason", "fleet fix");
        var service = service();
        var batch = service.create(BatchOperationType.MIGRATE, ERRORS, parameters, null);

        service.runNextPage();

        var done = repository.findById(batch.id()).orElseThrow();
        // p01 is on v2: not applicable; every other one (not found locally, or on v1) is migrated.
        assertThat(done.skipped()).isEqualTo(1);
        assertThat(done.succeeded()).isEqualTo(24);
        verify(operations).migrate(eq("p00"), eq(3), eq(Map.of("b", "b2")), eq(Map.of("x", "1")),
                eq("fleet fix (batch " + batch.id() + ")"), any());
        verify(operations, never()).migrate(eq("p01"), any(), any(), any(), any(), any());
    }

    @Test
    void migrateParametersAreCheckedWhenTheBatchIsCreated() {
        var service = service();
        assertThatThrownBy(() -> service.create(BatchOperationType.MIGRATE, ERRORS,
                io.mateu.workflow.domain.batch.BatchOperationParameters.migrate(null, null, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.create(BatchOperationType.MIGRATE, ERRORS,
                Map.of("reason", "r", "targetVersion", "latest"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a version number");
        assertThatThrownBy(() -> service.create(BatchOperationType.MIGRATE, ERRORS,
                Map.of("reason", "r", "var. ", "1"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a name");
        assertThatThrownBy(() -> service.create(BatchOperationType.MIGRATE,
                new BatchOperationFilter(null, "t", null, null, null, null, null, false, null), Map.of("reason", "r"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must name the definition");
        assertThat(service.create(BatchOperationType.MIGRATE, ERRORS,
                Map.of("reason", "r", "targetVersion", "v4", "map.a", ""), null).parameters())
                .containsEntry("targetVersion", "v4");
        assertThat(io.mateu.workflow.domain.batch.BatchOperationParameters.version(Map.of("v", "V4"), "v")).isEqualTo(4);
        assertThat(io.mateu.workflow.domain.batch.BatchOperationParameters.version(null, "v")).isNull();
        assertThat(io.mateu.workflow.domain.batch.BatchOperationParameters.prefixed(null, "map.")).isEmpty();
        assertThat(BatchOperationType.MIGRATE.isManualOperation()).isTrue();
    }
}
