package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.InlineExecution;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.EventFailures;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * The synchronous fast path, on the outbox: the pod that created a process handles that process's
 * outbox rows itself, one after another, instead of each one crossing the relay (and, in kafka mode,
 * the broker) before the next step can start.
 *
 * <h2>Why nothing can be lost</h2>
 * The rows are written, with the transitions, exactly as on the normal path; this only changes who
 * handles them. While a pod drives a process, the rows that process writes on the driving thread are
 * inserted already {@code InlineClaimed} by this pod (see {@link InlineDrive}), so no relay — which
 * only ever claims {@code Pending} — races for them. Each is handled through the very same
 * {@link ProcessDomainEventUseCase} the relays call, then marked {@code Sent}: dispatch before
 * marking, at-least-once, as the relays do. When the drive stops — the next step needs a Kafka
 * worker, a timer, a message, a human; the step budget is spent; a handler failed retryably — any
 * claimed rows go back to {@code Pending} and the relay carries on. If the pod dies mid-drive, its
 * claims stop being renewed and {@link InlineClaimSweeper} hands them back once the lease lapses.
 *
 * <h2>Single writer</h2>
 * Handlers take the process lock the way they always do. In embedded mode that is the process row
 * lock. In kafka mode it is the partition owner plus {@code @Version}: a conflict with the owner
 * (a parallel branch's worker reply landing there during the drive) fails one side optimistically,
 * which is the engine's existing answer to two writers; if it is this side, the drive stops and the
 * row goes back to the relay.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class InlineOutboxDriver implements InlineExecution {

    /**
     * Which engine instance holds a claim. Per instance rather than per JVM: several engines can
     * share one JVM (the multi-pod tests do), and one of them must never renew — or hand back — the
     * claims of another that has died.
     */
    final String POD = "pod-" + UUID.randomUUID();

    final JdbcTemplate jdbcTemplate;
    final ObjectProvider<ProcessDomainEventUseCase> processDomainEventUseCase;
    final OutboxSignal outboxSignal;
    final WorkflowMetrics workflowMetrics;

    @org.springframework.beans.factory.annotation.Value("${workflow.sync.inline.enabled:true}")
    boolean enabled = true;

    /** How many processes this pod drives inline at once; past it, new ones take the normal path. */
    @org.springframework.beans.factory.annotation.Value("${workflow.sync.inline.threads:8}")
    int threads = 8;

    /** Rows handled inline per invocation before the rest goes back to the relay. */
    @org.springframework.beans.factory.annotation.Value("${workflow.sync.inline.max-steps:64}")
    int maxSteps = 64;

    /** How long a claim holds without renewal; renewed every third of it while the pod is alive. */
    @org.springframework.beans.factory.annotation.Value("${workflow.sync.inline.claim-lease-ms:30000}")
    long claimLeaseMs = 30_000;

    private Semaphore slots;
    private ExecutorService drivers;
    private ScheduledExecutorService renewer;
    private final AtomicInteger driving = new AtomicInteger();

    @PostConstruct
    void start() {
        slots = new Semaphore(Math.max(1, threads));
        var counter = new AtomicInteger();
        drivers = Executors.newFixedThreadPool(Math.max(1, threads), runnable -> {
            var thread = new Thread(runnable, "sync-inline-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "sync-inline-claims");
            thread.setDaemon(true);
            return thread;
        });
        var every = Math.max(100, claimLeaseMs / 3);
        renewer.scheduleWithFixedDelay(this::renewClaims, every, every, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (drivers != null) drivers.shutdownNow();
        if (renewer != null) renewer.shutdownNow();
    }

    @Override
    public Slot reserve(String processId) {
        if (!enabled || !slots.tryAcquire()) {
            if (enabled) {
                workflowMetrics.syncInlineFallback("saturated");
            }
            return null;
        }
        return new Slot() {
            private boolean released;

            @Override
            public void claimDuring(Runnable creation) {
                InlineDrive.run(context(processId), () -> {
                    creation.run();
                    return null;
                });
            }

            @Override
            public void start() {
                try {
                    drivers.execute(() -> {
                        try {
                            drive(processId);
                        } finally {
                            release();
                        }
                    });
                } catch (RuntimeException e) {
                    // Could not even hand it to a thread: everything goes back to the relay now.
                    giveBack(processId);
                    release();
                }
            }

            @Override
            public void abandon() {
                giveBack(processId);
                release();
            }

            private synchronized void release() {
                if (!released) {
                    released = true;
                    slots.release();
                }
            }
        };
    }

    /** Handles this process's claimed rows, oldest first, until there are none or it must stop. */
    void drive(String processId) {
        driving.incrementAndGet();
        int handled = 0;
        try {
            while (true) {
                var row = nextClaimed(processId);
                if (row == null) {
                    return; // nothing left here: the next step is someone else's (or the process is done)
                }
                if (handled >= maxSteps) {
                    workflowMetrics.syncInlineFallback("budget");
                    return;
                }
                if (!renew(row.id())) {
                    workflowMetrics.syncInlineFallback("claim_lost");
                    return; // the claim lapsed and the relay may have it: stop touching this process
                }
                DomainEvent event;
                try {
                    event = (DomainEvent) pojoFromJson(row.payload(), OutboxMessages.messageClass(row.messageType()));
                } catch (Exception e) {
                    log.error("Outbox message {} cannot be deserialized, marking as Error", row.id(), e);
                    mark(row.id(), OutboxMessageStatus.Error);
                    continue;
                }
                try {
                    InlineDrive.run(context(processId), () -> {
                        processDomainEventUseCase.getObject().handle(new ProcessDomainEventCommand(event));
                        return null;
                    });
                    mark(row.id(), OutboxMessageStatus.Sent);
                    handled++;
                } catch (Exception e) {
                    if (EventFailures.isRetryable(e)) {
                        // A concurrent writer, a database hiccup: not ours to retry. The relay will.
                        log.info("Inline drive of process {} stops at message {}: {}", processId, row.id(), e.toString());
                        workflowMetrics.syncInlineFallback("failure");
                        return;
                    }
                    log.error("Outbox message {} cannot be processed inline, marking as Error", row.id(), e);
                    mark(row.id(), OutboxMessageStatus.Error);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Inline drive of process {} failed; its remaining work goes back to the relay: {}",
                    processId, e.toString());
            workflowMetrics.syncInlineFallback("failure");
        } finally {
            workflowMetrics.syncInlineSteps(handled);
            giveBack(processId);
            driving.decrementAndGet();
        }
    }

    private record Row(String id, String messageType, String payload) {
    }

    private Row nextClaimed(String processId) {
        var rows = jdbcTemplate.query(
                "SELECT id, message_type, payload FROM outbox_message_entity"
                        + " WHERE partition_key = ? AND status = ? AND claimed_by = ?"
                        + " ORDER BY timestamp, id",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)),
                processId, OutboxMessageStatus.InlineClaimed.name(), POD);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Re-asserts the claim on one row just before handling it; false if it is no longer ours. */
    private boolean renew(String id) {
        return jdbcTemplate.update(
                "UPDATE outbox_message_entity SET claim_until = ? WHERE id = ? AND status = ? AND claimed_by = ?",
                Timestamp.valueOf(claimUntil()), id, OutboxMessageStatus.InlineClaimed.name(), POD) == 1;
    }

    private void mark(String id, OutboxMessageStatus status) {
        jdbcTemplate.update(
                "UPDATE outbox_message_entity SET status = ?, claimed_by = NULL, claim_until = NULL"
                        + " WHERE id = ? AND claimed_by = ?",
                status.name(), id, POD);
    }

    /** Hands whatever this pod still claims for the process back to the relay, and wakes it. */
    void giveBack(String processId) {
        try {
            var released = jdbcTemplate.update(
                    "UPDATE outbox_message_entity SET status = ?, claimed_by = NULL, claim_until = NULL"
                            + " WHERE partition_key = ? AND status = ? AND claimed_by = ?",
                    OutboxMessageStatus.Pending.name(), processId, OutboxMessageStatus.InlineClaimed.name(), POD);
            if (released > 0) {
                outboxSignal.raise();
            }
        } catch (RuntimeException e) {
            // The lease backstop will return them.
            log.warn("Could not hand the inline claims of process {} back to the relay: {}", processId, e.toString());
        }
    }

    /** Keeps this pod's claims alive while it is alive; a dead pod's claims lapse on their own. */
    private void renewClaims() {
        try {
            jdbcTemplate.update(
                    "UPDATE outbox_message_entity SET claim_until = ? WHERE status = ? AND claimed_by = ?",
                    Timestamp.valueOf(claimUntil()), OutboxMessageStatus.InlineClaimed.name(), POD);
        } catch (RuntimeException e) {
            log.warn("Could not renew inline outbox claims: {}", e.toString());
        }
    }

    private InlineDrive.Context context(String processId) {
        return new InlineDrive.Context(processId, POD, claimUntil());
    }

    private LocalDateTime claimUntil() {
        return LocalDateTime.now().plusNanos(claimLeaseMs * 1_000_000L);
    }
}
