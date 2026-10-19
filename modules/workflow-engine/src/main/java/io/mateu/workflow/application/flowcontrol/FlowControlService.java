package io.mateu.workflow.application.flowcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.BusinessKeyPolicy;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StartPolicy;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.time.EngineClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-key flow control of process starts — a {@code startPolicy}'s debounce, throttle and singleton
 * (FLOW-CONTROL-PLAN.md §2). Every door that starts a process under a policy asks {@link #admit}; the
 * {@code FlowControlPass} calls {@link #releaseDue} to start what waited.
 *
 * <p>Order: debounce → singleton → throttle → start. A start released from a wait re-enters at the next
 * stage, so it honours the singleton and the throttle at the moment it actually starts. Every decision for
 * a key is made holding the key ({@link FlowControlStore#withKey}).
 */
@Service
@Slf4j
public class FlowControlService {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /** What a policy decided about one start. */
    public enum Outcome {
        STARTED, DEBOUNCED, THROTTLED, DROPPED, SKIPPED, REPLACED;

        /** Lower case, as metrics and the REST answer say it. */
        public String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Whether a process was started now. */
        public boolean started() {
            return this == STARTED || this == REPLACED;
        }
    }

    /**
     * @param processId   the process started (STARTED/REPLACED, when the door knows it) or the running one (SKIPPED)
     * @param businessKey the business key of that process, or of the start that waits
     * @param dueAt       when a waiting start (DEBOUNCED/THROTTLED) is due, or when a DROPPED one could pass
     * @param detail      a sentence for receipts and logs
     */
    public record Decision(Outcome outcome, String processId, String businessKey, LocalDateTime dueAt, String detail) {
    }

    /** What a door's start produced. */
    public record Started(String processId, String businessKey) {
    }

    /** How a door starts a process now. */
    @FunctionalInterface
    public interface Starter {
        Started start(FlowStart start);
    }

    /** A synchronous caller cannot wait for a later start: nothing is debounced or queued for it. */
    public enum Mode { DEFAULT, IMMEDIATE_ONLY }

    private final FlowControlStore store;
    private final ProcessRepository processes;
    private final ObjectProvider<CancelProcessUseCase> cancelProcessUseCase;
    private final ObjectProvider<IngressRouter> ingressRouter;
    private final WorkflowMetrics metrics;

    /**
     * How long a start whose process is not visible yet still counts as running for a singleton: a start
     * routed to the engine's ingress (an event trigger) is created asynchronously, and a second event
     * arriving before it must not start a second one.
     */
    @Value("${workflow.flow-control.creation-grace:PT5M}")
    Duration creationGrace = Duration.ofMinutes(5);

    public FlowControlService(FlowControlStore store, ProcessRepository processes,
                              ObjectProvider<CancelProcessUseCase> cancelProcessUseCase,
                              ObjectProvider<IngressRouter> ingressRouter, WorkflowMetrics metrics) {
        this.store = store;
        this.processes = processes;
        this.cancelProcessUseCase = cancelProcessUseCase;
        this.ingressRouter = ingressRouter;
        this.metrics = metrics;
    }

    /** The id a key's state is kept under: a SHA-256 of its scope and key. */
    public static String keyId(String scope, String flowKey) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((scope + "\n" + (flowKey == null ? "" : flowKey)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decides one start under its policy, starting it now through {@code starter} when it may run. */
    public Decision admit(FlowStart start, Starter starter, Mode mode) {
        var policy = start.policy();
        var decision = store.withKey(keyId(start.scope(), start.flowKey()), start.scope(), start.flowKey(), tx -> {
            var now = EngineClock.now();
            if (policy.debounce() != null && !start.debounced() && mode == Mode.DEFAULT) {
                return debounce(tx, start, now);
            }
            return admitNow(tx, start, starter, now, false, mode == Mode.DEFAULT);
        });
        metrics.flowControlDecision(start.workflowId(), start.scope(), decision.outcome().text());
        log.info("startPolicy of '{}' (key '{}'): {} — {}", start.scope(), start.flowKey(), decision.outcome(),
                decision.detail());
        return decision;
    }

    private Decision debounce(FlowControlStore.KeyTx tx, FlowStart start, LocalDateTime now) {
        var dueAt = now.plus(start.policy().debounceDuration());
        var existing = tx.debounce();
        var id = tx.state().keyId();
        var pending = new FlowControlStore.Pending(id, id, FlowControlStore.Kind.DEBOUNCE, start.workflowId(),
                write(start), dueAt, existing.map(FlowControlStore.Pending::enqueuedAt).orElse(now),
                existing.map(p -> p.absorbed() + 1).orElse(1));
        tx.savePending(pending);
        return new Decision(Outcome.DEBOUNCED, null, start.businessKey(), dueAt,
                "debounced until " + dueAt + " (" + pending.absorbed() + " start(s) folded into one; the last one's data wins)");
    }

    /**
     * Singleton, then throttle, then start.
     *
     * @param fromQueue the start comes from the head of the throttle queue (it is not queued behind itself)
     * @param mayQueue  whether a throttled start may wait (a synchronous caller cannot)
     */
    private Decision admitNow(FlowControlStore.KeyTx tx, FlowStart start, Starter starter, LocalDateTime now,
                              boolean fromQueue, boolean mayQueue) {
        var policy = start.policy();
        var state = tx.state();
        Process running = null;
        var singleton = policy.singletonMode();
        if (singleton != null) {
            var holder = runningOf(state, now);
            if (holder.isPresent()) {
                if (singleton == StartPolicy.Singleton.SKIP) {
                    var p = holder.get();
                    return new Decision(Outcome.SKIPPED, p.processId(), p.businessKey(), null,
                            "skipped: a start of this key is still running ("
                                    + (p.processId() == null ? "business key " + p.businessKey() : "process " + p.processId()) + ")");
                }
                running = holder.get().process();
            }
        }
        var throttle = policy.throttle();
        if (throttle != null) {
            var period = throttle.periodDuration();
            if (state.windowStart() == null || !now.isBefore(state.windowStart().plus(period))) {
                state = state.withWindow(now, 0);
            }
            var windowEnd = state.windowStart().plus(period);
            boolean full = state.windowCount() >= throttle.limit();
            boolean behindOthers = !fromQueue && !tx.queued().isEmpty();
            if (full || behindOthers) {
                tx.saveState(state);
                if (throttle.drops() || !mayQueue) {
                    return new Decision(Outcome.DROPPED, null, start.businessKey(), windowEnd,
                            "dropped: " + throttle.limit() + " start(s) per " + throttle.period() + " already admitted");
                }
                var queued = start.businessKey() == null
                        ? start.withBusinessKey(start.workflowId() + "-flow-" + UUID.randomUUID()) : start;
                tx.savePending(new FlowControlStore.Pending(nextQueueId(tx), state.keyId(),
                        FlowControlStore.Kind.THROTTLE, start.workflowId(), write(queued), windowEnd, now, 1));
                return new Decision(Outcome.THROTTLED, null, queued.businessKey(), windowEnd,
                        "throttled: queued until a window of " + throttle.period() + " has room (at the earliest " + windowEnd + ")");
            }
            state = state.withWindow(state.windowStart(), state.windowCount() + 1);
        }
        if (running != null) {
            log.info("startPolicy of '{}' (key '{}'): replacing running process {}", start.scope(), start.flowKey(),
                    running.getId());
            cancelProcessUseCase.getObject().handle(new CancelProcessCommand(running.getId()));
        }
        var started = starter.start(start);
        tx.saveState(state.withLastStart(started.processId(), started.businessKey(), now));
        return new Decision(running != null ? Outcome.REPLACED : Outcome.STARTED, started.processId(),
                started.businessKey(), null, running != null ? "started; replaced process " + running.getId() : "started");
    }

    /**
     * The id of a start joining the key's throttle queue: the key and a sequence number one past the last one
     * queued, so ids order the queue (FIFO) whatever the clocks of the pods that queued them say. Taken under
     * the key's lock, so it never repeats while the queue holds the previous one.
     */
    private static String nextQueueId(FlowControlStore.KeyTx tx) {
        var queue = tx.queued();
        long next = queue.isEmpty() ? 0 : sequenceOf(queue.getLast().id()) + 1;
        return tx.state().keyId().substring(0, 40) + "-" + String.format("%019d", next);
    }

    private static long sequenceOf(String queueId) {
        var dash = queueId.lastIndexOf('-');
        try {
            return Long.parseLong(queueId.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The last start of the key, when it is still running (or not created yet, within the grace). */
    private Optional<Holder> runningOf(FlowControlStore.KeyState state, LocalDateTime now) {
        if (state.lastProcessId() == null && state.lastBusinessKey() == null) {
            return Optional.empty();
        }
        Process process = null;
        if (state.lastProcessId() != null) {
            process = processes.findById(state.lastProcessId()).orElse(null);
        }
        if (process == null && state.lastBusinessKey() != null) {
            process = processes.findByBusinessKey(state.lastBusinessKey()).orElse(null);
        }
        if (process != null) {
            return BusinessKeyPolicy.ended(process.getStatus()) ? Optional.empty()
                    : Optional.of(new Holder(process.getId(), process.getBusinessKey(), process));
        }
        // Not visible yet: routed for creation and not created (or refused). Running, for a while.
        var startedAt = state.lastStartedAt();
        return startedAt != null && now.isBefore(startedAt.plus(creationGrace))
                ? Optional.of(new Holder(state.lastProcessId(), state.lastBusinessKey(), null)) : Optional.empty();
    }

    private record Holder(String processId, String businessKey, Process process) {
    }

    // ── releasing ────────────────────────────────────────────────────────────────────────────────

    /** Starts what is due: debounces whose quiet period ended, queued starts whose window has room. */
    public int releaseDue() {
        int released = 0;
        for (var keyId : store.dueKeys(EngineClock.now(), 500)) {
            released += store.withKey(keyId, null, null, tx -> release(tx, EngineClock.now()));
        }
        return released;
    }

    /** The earliest moment something waiting falls due. */
    public Optional<LocalDateTime> nextDueAt() {
        return store.nextDueAt();
    }

    private int release(FlowControlStore.KeyTx tx, LocalDateTime now) {
        int released = 0;
        var debounce = tx.debounce();
        if (debounce.isPresent() && !debounce.get().dueAt().isAfter(now)) {
            tx.deletePending(debounce.get().id());
            var start = read(debounce.get().requestJson()).asDebounced();
            var decision = admitNow(tx, start, this::route, now, false, true);
            record(start, decision, debounce.get().absorbed());
            released++;
        }
        for (var queued : tx.queued()) {
            var start = read(queued.requestJson());
            var throttle = start.policy().throttle();
            var state = tx.state();
            if (throttle != null && state.windowStart() != null
                    && now.isBefore(state.windowStart().plus(throttle.periodDuration()))
                    && state.windowCount() >= throttle.limit()) {
                // No room yet: everything behind it is due when this window closes.
                var windowEnd = state.windowStart().plus(throttle.periodDuration());
                tx.queued().forEach(waiting -> tx.savePending(waiting.withDueAt(windowEnd)));
                break;
            }
            tx.deletePending(queued.id());
            var decision = admitNow(tx, start, this::route, now, true, true);
            record(start, decision, 1);
            released++;
        }
        return released;
    }

    private void record(FlowStart start, Decision decision, int absorbed) {
        metrics.flowControlDecision(start.workflowId(), start.scope(), "released");
        metrics.flowControlDecision(start.workflowId(), start.scope(), decision.outcome().text());
        log.info("startPolicy of '{}' (key '{}'): a waiting start ({} folded) was released — {}", start.scope(),
                start.flowKey(), absorbed, decision.detail());
    }

    /** A released start goes through the ingress, as cron and the triggers start processes. */
    private Started route(FlowStart start) {
        var variables = start.variables().entrySet().stream().map(e -> new Variable(e.getKey(), e.getValue())).toList();
        ingressRouter.getObject().route(new ProcessCreationRequested(start.workflowId(), start.businessKey(), variables,
                null, start.caller()));
        return new Started(null, start.businessKey());
    }

    private static String write(FlowStart start) {
        try {
            return JSON.writeValueAsString(start);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not write a waiting start", e);
        }
    }

    private static FlowStart read(String json) {
        try {
            return JSON.readValue(json, FlowStart.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unreadable waiting start", e);
        }
    }
}
