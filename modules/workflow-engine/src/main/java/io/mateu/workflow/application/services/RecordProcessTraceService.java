package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Writes a finished process out as one trace: a span for the process, and a span for each step
 * execution that ran, built from the timestamps the engine already recorded.
 *
 * <p><b>Why the trace is built at the end rather than as the process runs.</b> A span has to be
 * started and ended by the same object in the same JVM, and a workflow step obliges on neither
 * count: it starts in the transaction that dispatched it and ends in whichever pod happens to
 * receive the worker's reply, minutes or days later, across a broker and possibly a restart. Live
 * instrumentation can only ever describe the hop it is inside — which is what a trace made of
 * dispatch and relay spans looks like, and why it reads as a pile of unrelated fragments rather
 * than as a process. The durable record does not have that problem: {@code startedAt} and
 * {@code finishedAt} are on the row, so the span can be built afterwards and be exactly right.
 *
 * <p><b>What comes out.</b> One trace per process, whose root is the process and whose children are
 * its steps, each covering the time that step actually took. Steps that ran one after another read
 * as consecutive siblings; steps that ran in parallel read as overlapping ones. That is the picture
 * — "this ran, then this, then those two together" — and it falls out of the timestamps rather than
 * having to be reconstructed from causality.
 *
 * <p>Called from each seam where a process reaches a terminal status, which are transitions rather
 * than states, so a redelivered event does not emit the trace twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordProcessTraceService {

    final StepExecutionRepository stepExecutionRepository;
    final WorkflowTracing workflowTracing;
    final ProcessTrace processTrace;

    public void processReachedTerminalStatus(Process process) {
        if (process == null || process.getId() == null) {
            return;
        }
        try {
            record(process);
        } catch (RuntimeException e) {
            // A description of the work must never be able to fail the work. This runs on the path
            // that completes a process, so a broken exporter or an unreadable step must cost the
            // trace and nothing else.
            log.debug("Could not record the trace of process {}", process.getId(), e);
        }
    }

    private void record(Process process) {
        var startedAt = process.getStarted() != null ? process.getStarted() : process.getCreated();
        var finishedAt = process.getFinished() != null ? process.getFinished() : LocalDateTime.now();
        if (startedAt == null) {
            return;
        }

        var processTags = new LinkedHashMap<String, String>();
        processTags.put("eventconductor.process.id", process.getId());
        if (process.getBusinessKey() != null) {
            processTags.put("eventconductor.process.businessKey", process.getBusinessKey());
        }
        processTags.put("eventconductor.workflow.id", process.getWorkflowDefinitionId());
        processTags.put("eventconductor.workflow.version", String.valueOf(process.getWorkflowDefinitionVersion()));
        processTags.put("eventconductor.process.status", String.valueOf(process.getStatus()));

        // The process's own span hangs off the derived anchor, which is never emitted — so this is
        // the root of the trace, and it is the same trace the live spans for this process joined
        // while it was still running.
        var processSpan = workflowTracing.recordSpan(
                processTrace.anchorFor(process.getId()),
                process.getName() == null || process.getName().isBlank()
                        ? process.getWorkflowDefinitionId() : process.getName(),
                instant(startedAt), instant(finishedAt), processTags);
        if (processSpan == null) {
            // No tracer, or nothing sampled: there is no parent to hang the steps off, and a step
            // span with no process above it would be a fragment of exactly the kind this replaces.
            return;
        }

        stepExecutionRepository.findByProcess(process).stream()
                // Ordered by when each one started, so the waterfall reads top to bottom in the
                // order the work happened rather than in whatever order the store returned.
                .sorted(java.util.Comparator.comparing(StepExecution::getStartedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .forEach(execution -> recordStep(execution, processSpan));
    }

    private void recordStep(StepExecution execution, String processSpan) {
        // A step that never started has nothing to draw: it was cancelled while the process wound
        // up, or its branch was never taken. Emitting a zero-width span for each would fill the
        // waterfall with the steps that did not run and bury the ones that did.
        if (execution.getStartedAt() == null) {
            return;
        }
        // Still in flight when the process ended — a branch abandoned at an END step. Its span ends
        // where the process did, which is when it stopped mattering.
        var finishedAt = execution.getFinishedAt() != null ? execution.getFinishedAt() : LocalDateTime.now();

        var tags = new LinkedHashMap<String, String>();
        tags.put("eventconductor.step.id", execution.getStepId());
        tags.put("eventconductor.step.executionId", execution.id());
        tags.put("eventconductor.step.status", String.valueOf(execution.getStatus()));
        tags.put("eventconductor.step.attempts", String.valueOf(execution.getAttemptCount()));
        var step = stepOf(execution);
        if (step != null) {
            tags.put("eventconductor.step.type", String.valueOf(step.type()));
            // Only ACTION steps have one; a tag whose value is absent is left off rather than
            // written as the string "null", which is what a reader would then have to filter out.
            if (step.topic() != null && !step.topic().isBlank()) {
                tags.put("eventconductor.step.topic", step.topic());
            }
        }

        workflowTracing.recordSpan(processSpan, nameOf(execution, step),
                instant(execution.getStartedAt()), instant(finishedAt), tags);
    }

    /** The step's name as its author wrote it, falling back to its id when the JSON cannot be read. */
    private String nameOf(StepExecution execution, Step step) {
        return step == null || step.name() == null || step.name().isBlank()
                ? execution.getStepId() : step.name();
    }

    private Step stepOf(StepExecution execution) {
        try {
            return execution.getStepJson() == null ? null : pojoFromJson(execution.getStepJson(), Step.class);
        } catch (RuntimeException e) {
            log.debug("Could not read the step JSON of step execution {}", execution.id(), e);
            return null;
        }
    }

    private static Instant instant(LocalDateTime at) {
        return at.atZone(ZoneId.systemDefault()).toInstant();
    }
}
