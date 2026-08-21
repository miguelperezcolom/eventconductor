package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ProcessAnalyticsService.TimeWindow;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProcessAnalyticsServiceTest {

    @Mock ProcessRepository processRepository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock WorkflowDefinitionRepository workflowDefinitionRepository;

    @InjectMocks ProcessAnalyticsService service;

    /**
     * The service reads its snapshot through the ports' analytics projections, whose in-memory
     * implementations are default methods over {@code findAll()}. Mockito does not run default
     * methods, so they are routed to the real ones — the aggregates over the projections, and the
     * projections over the {@code findAll()} stubs each test sets up. That keeps these tests exercising the shipped in-memory behaviour rather
     * than a second copy of it written here.
     */
    @BeforeEach
    void useTheRealInMemoryProjections() {
        lenient().when(processRepository.findAnalyticsRows(any(), any())).thenCallRealMethod();
        lenient().when(stepExecutionRepository.findAnalyticsRows(any(), any())).thenCallRealMethod();
        lenient().when(processRepository.aggregateProcesses(any(), any())).thenCallRealMethod();
        lenient().when(stepExecutionRepository.aggregateSteps(any(), any())).thenCallRealMethod();
    }

    private final AtomicInteger sequence = new AtomicInteger();
    // Fixed early-morning hour so base + a few hours never crosses midnight
    // (createdPerDay groups by calendar day).
    private final LocalDateTime base = LocalDateTime.now().minusDays(1).toLocalDate().atTime(4, 0);

    private WorkflowDefinition definition(String id, String name) {
        return new WorkflowDefinition(id, name, 1, null, false, 0, false, null, 0, List.of());
    }

    private Process process(String definitionId, ProcessStatus status, LocalDateTime created, Duration duration) {
        var finished = duration != null ? created.plus(duration) : null;
        return Process.builder()
                .id("p-" + sequence.incrementAndGet())
                .name(definitionId)
                .workflowDefinitionId(definitionId)
                .status(status)
                .created(created)
                .started(created)
                .finished(finished)
                .build();
    }

    private StepExecution step(String processId, String stepId, StepExecutionStatus status,
                               long order, Duration duration) {
        return StepExecution.builder()
                .id("se-" + sequence.incrementAndGet())
                .processId(processId)
                .workflowDefinitionId("def-1")
                .stepId(stepId)
                .status(status)
                .order(order)
                .startedAt(base)
                .finishedAt(duration != null ? base.plus(duration) : null)
                .build();
    }

    @Test
    void countsRatesAndThroughputPerDefinitionAndWindow() {
        var inWindow1 = process("def-1", ProcessStatus.COMPLETED, base, Duration.ofSeconds(10));
        var inWindow2 = process("def-1", ProcessStatus.ERROR, base.plusHours(1), Duration.ofSeconds(20));
        var inWindow3 = process("def-1", ProcessStatus.RUNNING, base.plusHours(2), null);
        var inWindow4 = process("def-1", ProcessStatus.CANCELLED, base.plusHours(3), Duration.ofSeconds(5));
        var outOfWindow = process("def-1", ProcessStatus.COMPLETED, base.minusDays(30), Duration.ofSeconds(1));
        var otherDefinition = process("def-2", ProcessStatus.COMPLETED, base, Duration.ofSeconds(1));
        lenient().when(processRepository.findAll()).thenReturn(
                List.of(inWindow1, inWindow2, inWindow3, inWindow4, outOfWindow, otherDefinition));
        lenient().when(stepExecutionRepository.findAll()).thenReturn(List.of());
        lenient().when(workflowDefinitionRepository.findAll()).thenReturn(List.of(definition("def-1", "Onboarding")));
        lenient().when(workflowDefinitionRepository.findById("def-1")).thenReturn(Optional.of(definition("def-1", "Onboarding")));

        var analytics = service.analyze("def-1", TimeWindow.lastDays(7)).orElseThrow();

        assertThat(analytics.workflowDefinitionName()).isEqualTo("Onboarding");
        assertThat(analytics.totalInstances()).isEqualTo(4);
        assertThat(analytics.instancesByStatus())
                .containsEntry(ProcessStatus.COMPLETED, 1L)
                .containsEntry(ProcessStatus.ERROR, 1L)
                .containsEntry(ProcessStatus.RUNNING, 1L)
                .containsEntry(ProcessStatus.CANCELLED, 1L);
        assertThat(analytics.completionRatePct()).isEqualTo(25.0);
        assertThat(analytics.errorRatePct()).isEqualTo(25.0);
        assertThat(analytics.cancellationRatePct()).isEqualTo(25.0);
        assertThat(analytics.createdPerDay()).containsEntry(base.toLocalDate(), 4L);
    }

    @Test
    void processDurationAverageAndP95AreComputedOverFinishedInstances() {
        var durations = List.of(10, 20, 30, 40, 100);
        var processes = durations.stream()
                .map(seconds -> process("def-1", ProcessStatus.COMPLETED, base, Duration.ofSeconds(seconds)))
                .toList();
        lenient().when(processRepository.findAll()).thenReturn(processes);
        lenient().when(stepExecutionRepository.findAll()).thenReturn(List.of());
        lenient().when(workflowDefinitionRepository.findAll()).thenReturn(List.of(definition("def-1", "Onboarding")));
        lenient().when(workflowDefinitionRepository.findById("def-1")).thenReturn(Optional.of(definition("def-1", "Onboarding")));

        var analytics = service.analyze("def-1", TimeWindow.all()).orElseThrow();

        assertThat(analytics.processDuration().samples()).isEqualTo(5);
        assertThat(analytics.processDuration().average()).isEqualTo(Duration.ofSeconds(40));
        // Nearest-rank p95 over 5 samples = the 5th value.
        assertThat(analytics.processDuration().p95()).isEqualTo(Duration.ofSeconds(100));
    }

    @Test
    void flagsSlowestStepAsBottleneckAndCountsStuckAndFailedSteps() {
        var instance = process("def-1", ProcessStatus.RUNNING, base, null);
        lenient().when(processRepository.findAll()).thenReturn(List.of(instance));
        lenient().when(stepExecutionRepository.findAll()).thenReturn(List.of(
                step(instance.getId(), "validate", StepExecutionStatus.COMPLETED, 0, Duration.ofSeconds(2)),
                step(instance.getId(), "provision", StepExecutionStatus.COMPLETED, 1, Duration.ofSeconds(60)),
                step(instance.getId(), "notify", StepExecutionStatus.PENDING, 2, null),
                step(instance.getId(), "bill", StepExecutionStatus.ERROR, 3, Duration.ofSeconds(1))));
        lenient().when(workflowDefinitionRepository.findAll()).thenReturn(List.of(definition("def-1", "Onboarding")));
        lenient().when(workflowDefinitionRepository.findById("def-1")).thenReturn(Optional.of(definition("def-1", "Onboarding")));

        var analytics = service.analyze("def-1", TimeWindow.all()).orElseThrow();

        assertThat(analytics.bottleneckStepId()).isEqualTo("provision");
        var byId = analytics.steps().stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.stepId(), s -> s));
        assertThat(byId.get("provision").bottleneck()).isTrue();
        assertThat(byId.get("provision").duration().average()).isEqualTo(Duration.ofSeconds(60));
        assertThat(byId.get("validate").bottleneck()).isFalse();
        assertThat(byId.get("notify").active()).isEqualTo(1);
        assertThat(byId.get("notify").duration().samples()).isZero();
        assertThat(byId.get("bill").failed()).isEqualTo(1);
        // Steps come out in flow order.
        assertThat(analytics.steps().stream().map(s -> s.stepId()))
                .containsExactly("validate", "provision", "notify", "bill");
    }

    @Test
    void resolvesDefinitionByNameCaseInsensitively() {
        lenient().when(processRepository.findAll()).thenReturn(List.of());
        lenient().when(stepExecutionRepository.findAll()).thenReturn(List.of());
        lenient().when(workflowDefinitionRepository.findAll()).thenReturn(List.of(definition("def-1", "Onboarding")));
        lenient().when(workflowDefinitionRepository.findById("def-1")).thenReturn(Optional.of(definition("def-1", "Onboarding")));

        assertThat(service.analyze("onboarding", TimeWindow.all())).isPresent();
        assertThat(service.analyze("def-1", TimeWindow.all())).isPresent();
        assertThat(service.analyze("nope", TimeWindow.all())).isEmpty();
    }

    @Test
    void analyzeAllIncludesDefinitionsWithoutInstances() {
        lenient().when(processRepository.findAll()).thenReturn(List.of());
        lenient().when(stepExecutionRepository.findAll()).thenReturn(List.of());
        lenient().when(workflowDefinitionRepository.findAll()).thenReturn(List.of(definition("def-1", "Onboarding")));
        lenient().when(workflowDefinitionRepository.findById("def-1")).thenReturn(Optional.of(definition("def-1", "Onboarding")));

        var all = service.analyzeAll(TimeWindow.lastDays(7));

        assertThat(all).hasSize(1);
        assertThat(all.get(0).totalInstances()).isZero();
        assertThat(all.get(0).completionRatePct()).isZero();
        assertThat(all.get(0).processDuration().samples()).isZero();
        assertThat(all.get(0).bottleneckStepId()).isNull();
    }

    @Test
    void windowSelectionIsByCreationTime() {
        var window = new TimeWindow(base.minusHours(1), base.plusHours(1));
        assertThat(window.contains(base)).isTrue();
        assertThat(window.contains(base.plusHours(2))).isFalse();
        assertThat(window.contains(null)).isFalse();
        assertThat(TimeWindow.all().contains(LocalDate.of(2000, 1, 1).atStartOfDay())).isTrue();
    }
}
