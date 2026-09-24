package io.mateu.workflow.application.sync;

import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Turns successive snapshots of a process — its status, its steps, its log — into the stream of
 * progress events a synchronous caller watches over SSE.
 *
 * <p>Built on persisted state only, read on every tick: nothing is added to a transition, and the
 * stream works the same whichever pod or shard runs the process.
 *
 * <p><b>Ids and resuming.</b> Event ids never decrease: the prefix is the moment the event describes
 * (epoch millis) or, if that is earlier than an event already sent, the moment of that one. A caller
 * that reconnects with {@code Last-Event-ID} is sent what happened from {@link #RESUME_LOOKBACK_MS}
 * before that moment on — at-least-once, because a row committed late can carry a time earlier than
 * events already sent. Every event's data has a stable {@code key} (the same fact always has the
 * same key), so a caller drops what it has already seen.
 */
public final class InvocationProgress {

    /** One event to send: its SSE name, its id, and what goes in {@code data}. */
    public record Event(String name, String id, Object data) {
    }

    private final Map<String, StepExecutionStatus> stepStatus = new HashMap<>();
    private final Map<String, Step> steps = new HashMap<>();
    private final Set<String> seenLogs = new HashSet<>();
    private final long resumeAfterMillis;
    private ProcessStatus lastStatus;
    private int sequence;
    private long lastMillis = Long.MIN_VALUE;

    /** How far before the resume point a reconnect looks again, to catch rows committed late. */
    public static final long RESUME_LOOKBACK_MS = 5_000;

    /** @param lastEventId the {@code Last-Event-ID} the caller reconnected with, or null */
    public InvocationProgress(String lastEventId) {
        var resumePoint = millisOf(lastEventId);
        this.resumeAfterMillis = resumePoint == Long.MIN_VALUE ? Long.MIN_VALUE : resumePoint - RESUME_LOOKBACK_MS;
    }

    /** The events this snapshot adds to what has already been sent, oldest first. */
    public List<Event> next(Process process, List<StepExecution> executions, List<LogMessage> logs) {
        var events = new ArrayList<Event>();
        if (process.getStatus() != lastStatus) {
            lastStatus = process.getStatus();
            var data = new LinkedHashMap<String, Object>();
            data.put("key", "status:" + process.getStatus());
            data.put("processId", process.getId());
            data.put("processStatus", process.getStatus() == null ? null : process.getStatus().name());
            events.add(event("status", System.currentTimeMillis(), data));
        }
        var changed = new ArrayList<Pending>();
        for (var execution : executions) {
            var status = execution.getStatus();
            if (status == null || status == StepExecutionStatus.CREATED) {
                continue; // not started: nothing has happened to it yet
            }
            if (status == stepStatus.put(execution.id(), status)) {
                continue;
            }
            var at = execution.getFinishedAt() != null ? execution.getFinishedAt() : execution.getStartedAt();
            var millis = millisOf(at);
            if (millis < resumeAfterMillis) {
                continue;
            }
            var step = stepOf(execution);
            var data = new LinkedHashMap<String, Object>();
            data.put("key", "step:" + execution.id() + ":" + status.name());
            data.put("stepId", execution.getStepId());
            data.put("stepName", step == null ? null : step.name());
            data.put("type", step == null || step.type() == null ? null : step.type().name());
            data.put("status", status.name());
            data.put("at", at);
            changed.add(new Pending("step", millis, data));
        }
        var stepIdByExecution = new HashMap<String, String>();
        executions.forEach(execution -> stepIdByExecution.put(execution.id(), execution.getStepId()));
        logs.stream()
                .sorted(Comparator.comparing(LogMessage::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(log -> {
                    if (log.getId() == null || !seenLogs.add(log.getId())) {
                        return;
                    }
                    var millis = millisOf(log.getTimestamp());
                    if (millis < resumeAfterMillis) {
                        return;
                    }
                    var data = new LinkedHashMap<String, Object>();
                    data.put("key", "log:" + log.getId());
                    data.put("stepId", stepIdByExecution.get(log.getStepExecutionId()));
                    data.put("level", log.getMessageType());
                    data.put("message", log.getMessage());
                    data.put("at", log.getTimestamp());
                    changed.add(new Pending("log", millis, data));
                });
        // Oldest first by the moment each describes; the ids are assigned in that order, so they rise.
        changed.sort(Comparator.comparingLong(Pending::millis));
        changed.forEach(pending -> events.add(event(pending.name(), pending.millis(), pending.data())));
        return events;
    }

    /** An event for something the stream decides itself (the reply, the deadline), stamped now. */
    public Event now(String name, Object data) {
        return event(name, System.currentTimeMillis(), data);
    }

    private record Pending(String name, long millis, Object data) {
    }

    private Event event(String name, long millis, Object data) {
        lastMillis = Math.max(lastMillis, millis);
        return new Event(name, String.format("%013d-%06d", lastMillis, ++sequence), data);
    }

    private Step stepOf(StepExecution execution) {
        return steps.computeIfAbsent(execution.id(), id -> {
            try {
                return pojoFromJson(execution.getStepJson(), Step.class);
            } catch (Exception e) {
                return null;
            }
        });
    }

    private static long millisOf(LocalDateTime at) {
        return at == null ? System.currentTimeMillis() : at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static long millisOf(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return Long.MIN_VALUE;
        }
        var dash = lastEventId.indexOf('-');
        try {
            return Long.parseLong(dash < 0 ? lastEventId : lastEventId.substring(0, dash));
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}
