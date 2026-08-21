package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TRACE-07..10 — the spans a finished process is written out as, asserted on the timestamps rather
 * than on the run.
 *
 * <p>The end-to-end suite pins the shape of the tree; this pins the thing the tree is made of. A
 * span is built from what the step execution row says about when the step ran, so two steps that
 * overlapped come out as spans that overlap — and that is worth asserting directly, because the
 * embedded harness runs its worker on the calling thread and can therefore never produce two steps
 * that really did run at the same time. Here they can be handed timestamps that overlap by
 * construction.
 */
class RecordProcessTraceServiceTest {

    /** One emitted span, flattened to what these tests care about. */
    private record Span(String parent, String traceParent, String name,
                        Instant startedAt, Instant finishedAt, Map<String, String> tags) {
    }

    private final List<Span> emitted = new ArrayList<>();
    private StepExecutionRepository stepExecutionRepository;
    private RecordProcessTraceService service;

    @BeforeEach
    void setUp() {
        emitted.clear();
        stepExecutionRepository = mock(StepExecutionRepository.class);
        service = new RecordProcessTraceService(stepExecutionRepository, new WorkflowTracing() {
            private int next;

            @Override
            public String recordSpan(String parentTraceParent, String name, Instant startedAt,
                                     Instant finishedAt, Map<String, String> tags) {
                var traceId = parentTraceParent.split("-")[1];
                var spanId = String.format("%016x", ++next);
                emitted.add(new Span(parentTraceParent, "00-" + traceId + "-" + spanId + "-01",
                        name, startedAt, finishedAt, new LinkedHashMap<>(tags)));
                return "00-" + traceId + "-" + spanId + "-01";
            }
        }, new ProcessTrace(1.0));
    }

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 21, 10, 0, 0);

    private Process process() {
        return Process.builder()
                .id("p-1")
                .name("Order fulfilment")
                .businessKey("order-42")
                .workflowDefinitionId("orders")
                .workflowDefinitionVersion(3)
                .status(ProcessStatus.COMPLETED)
                .created(T0)
                .started(T0)
                .finished(T0.plusSeconds(10))
                .variables(List.of())
                .build();
    }

    private StepExecution execution(String stepId, LocalDateTime startedAt, LocalDateTime finishedAt) {
        return StepExecution.builder()
                .id("se-" + stepId)
                .processId("p-1")
                .stepId(stepId)
                .status(StepExecutionStatus.COMPLETED)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .variables(List.of())
                .build();
    }

    private void given(StepExecution... executions) {
        when(stepExecutionRepository.findByProcess(any())).thenReturn(List.of(executions));
    }

    private static Instant at(LocalDateTime when) {
        return when.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Span span(String name) {
        return emitted.stream().filter(s -> name.equals(s.name())).findFirst().orElseThrow();
    }

    /** TRACE-07. Two steps that overlapped come out as two spans that overlap. */
    @Test
    void stepsThatRanAtTheSameTimeProduceOverlappingSpans() {
        given(execution("a", T0.plusSeconds(1), T0.plusSeconds(6)),
                execution("b", T0.plusSeconds(2), T0.plusSeconds(5)));

        service.processReachedTerminalStatus(process());

        var a = span("a");
        var b = span("b");
        assertThat(b.startedAt()).isAfter(a.startedAt()).isBefore(a.finishedAt());
        assertThat(b.finishedAt()).isBefore(a.finishedAt());
        assertThat(a.parent())
                .as("and they are siblings: both hang off the process span, not off each other")
                .isEqualTo(b.parent());
    }

    /** TRACE-08. The process span covers the process, and the steps hang off it. */
    @Test
    void theProcessSpanIsTheRootAndCoversTheWholeRun() {
        given(execution("a", T0.plusSeconds(1), T0.plusSeconds(2)));

        service.processReachedTerminalStatus(process());

        var root = span("Order fulfilment");
        assertThat(root.startedAt()).isEqualTo(at(T0));
        assertThat(root.finishedAt()).isEqualTo(at(T0.plusSeconds(10)));
        assertThat(root.parent())
                .as("the root hangs off the derived anchor, which is never emitted")
                .isEqualTo(new ProcessTrace(1.0).anchorFor("p-1"));
        assertThat(span("a").parent()).isEqualTo(root.traceParent());
        assertThat(root.tags())
                .containsEntry("eventconductor.process.businessKey", "order-42")
                .containsEntry("eventconductor.workflow.id", "orders")
                .containsEntry("eventconductor.process.status", "COMPLETED");
    }

    /**
     * TRACE-09. A step that never started draws nothing. A process that ends at an END step leaves
     * whole branches never taken, and a zero-width span for each of them would bury the steps that
     * did run under the ones that did not.
     */
    @Test
    void stepsThatNeverRanAreNotDrawn() {
        given(execution("ran", T0.plusSeconds(1), T0.plusSeconds(2)),
                execution("never-taken", null, null));

        service.processReachedTerminalStatus(process());

        assertThat(emitted).extracting(Span::name).containsExactly("Order fulfilment", "ran");
    }

    /** TRACE-10. Steps come out in the order they ran, so the waterfall reads top to bottom. */
    @Test
    void spansAreEmittedInTheOrderTheStepsRan() {
        given(execution("third", T0.plusSeconds(7), T0.plusSeconds(8)),
                execution("first", T0.plusSeconds(1), T0.plusSeconds(2)),
                execution("second", T0.plusSeconds(3), T0.plusSeconds(4)));

        service.processReachedTerminalStatus(process());

        assertThat(emitted).extracting(Span::name)
                .containsExactly("Order fulfilment", "first", "second", "third");
    }

    /** TRACE-11. Nothing here may fail the process it is describing. */
    @Test
    void aFailureWhileTracingIsSwallowed() {
        when(stepExecutionRepository.findByProcess(any())).thenThrow(new IllegalStateException("store is down"));

        service.processReachedTerminalStatus(process());

        assertThat(emitted).extracting(Span::name).containsExactly("Order fulfilment");
    }
}
