package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's asynchronous boundary is a database row: an event is written inside the transaction
 * that produced it and published by a relay thread some time afterwards. Nothing automatic bridges
 * that — the instrumentation sees a write in one trace and, later, an unrelated send — so a process
 * used to read as a trace per hop however carefully the rest was instrumented.
 *
 * <p>What has to hold is the round trip: whatever context existed when the event was produced is on
 * the row, and the relay publishes inside it. This exercises that contract against the port rather
 * than against Micrometer, which is the layer the engine actually depends on — there is no tracer
 * here at all, and there is not meant to be.
 */
class OutboxTraceContinuityTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /** A tracing port that knows one trace, and records what it was asked to resume. */
    private static final class RecordingTracing implements WorkflowTracing {
        private final String current;
        final List<String> resumed = new ArrayList<>();
        boolean ranInside;

        RecordingTracing(String current) {
            this.current = current;
        }

        @Override
        public String currentTraceParent() {
            return current;
        }

        @Override
        public void continuing(String traceParent, String spanName, Runnable work) {
            resumed.add(traceParent);
            ranInside = true;
            work.run();
        }

        @Override
        public <T> T span(String name, Map<String, String> tags, Supplier<T> work) {
            return work.get();
        }
    }

    private DomainEvent anEvent() {
        return new RetryProcessRequested("p-1");
    }

    @Test
    void theRowCarriesTheTraceTheEventWasProducedIn() {
        var tracing = new RecordingTracing(TRACEPARENT);

        var row = new OutboxMessageEntity(anEvent(), tracing.currentTraceParent());

        assertThat(row.getTraceParent()).isEqualTo(TRACEPARENT);
    }

    @Test
    void aRowWrittenWithNothingBeingTracedCarriesNothing() {
        // The normal case: tracing is off until an endpoint and a sampling probability are set.
        var tracing = new RecordingTracing(null);

        var row = new OutboxMessageEntity(anEvent(), tracing.currentTraceParent());

        assertThat(row.getTraceParent()).isNull();
    }

    @Test
    void theRelayPublishesInsideTheTraceOnTheRow() {
        var tracing = new RecordingTracing(TRACEPARENT);
        var row = new OutboxMessageEntity(anEvent(), TRACEPARENT);
        var published = new ArrayList<DomainEvent>();

        // What OutboxDrain does with each claimed row.
        tracing.continuing(row.getTraceParent(), "outbox relay",
                () -> published.add(anEvent()));

        assertThat(tracing.resumed).containsExactly(TRACEPARENT);
        assertThat(published).hasSize(1);
    }

    @Test
    void theNoOpPortRunsTheWorkAndDescribesNothing() {
        // The engine with no tracing on the classpath: everything still happens, nothing is traced.
        var published = new ArrayList<String>();

        WorkflowTracing.NOOP.continuing(TRACEPARENT, "outbox relay", () -> published.add("sent"));

        assertThat(WorkflowTracing.NOOP.currentTraceParent()).isNull();
        assertThat(published).containsExactly("sent");
        assertThat(WorkflowTracing.NOOP.span("x", Map.of(), () -> "result")).isEqualTo("result");
    }
}
