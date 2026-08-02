package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Dispatches committed outbox messages back into the engine, in embedded mode.
 *
 * <p>Unlike the Kafka {@link OutboxRelay}, this one keeps its leader lock on purpose. "Delivery"
 * here is {@code processDomainEventUseCase.handle} — the engine running a step synchronously,
 * taking the process lock and its own connections. Claiming rows with {@code FOR UPDATE SKIP
 * LOCKED} would hold those row locks, and a database transaction, across all of that: long
 * transactions and a plausible deadlock against the work being dispatched. Making embedded
 * dispatch concurrent is a separate change with a different risk profile.
 *
 * <p>What it does take from that work is the bounded fetch: it used to load the entire pending
 * outbox on every cycle.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@ConditionalOnProperty(name = "workflow.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class EmbeddedOutboxRelay {

    // Advisory lock ids in use: 222333444 (CronStartScheduler), 444555666 (here),
    // 777888999 (TimeoutScheduler). Keep them distinct.
    private static final long LOCK_ID = 444555666L;

    final OutboxMessageEntityRepository outboxMessageEntityRepository;
    final ProcessDomainEventUseCase processDomainEventUseCase;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;
    final OutboxSignal outboxSignal;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox-poll-interval-ms:500}")
    long pollIntervalMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.batch-size:100}")
    int batchSize;

    @PostConstruct
    public void iterate() {
        var thread = new Thread(() -> {
            try {
                while (true) {
                    try {
                        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                            if (!dbLockDialect.tryLock(con, LOCK_ID)) return null;
                            try {
                                outboxMessageEntityRepository.findByStatusOrderByTimestamp(
                                        OutboxMessageStatus.Pending.name(),
                                        org.springframework.data.domain.PageRequest.of(0, batchSize)).forEach(m -> {
                                    log.debug("Processing embedded outbox message {}", m.getId());
                                    DomainEvent event;
                                    try {
                                        event = (DomainEvent) pojoFromJson(m.getPayload(), OutboxMessages.messageClass(m.getMessageType()));
                                    } catch (Exception e) {
                                        // Poison message: retrying can never succeed, park it as Error.
                                        log.error("Outbox message {} cannot be deserialized, marking as Error", m.getId(), e);
                                        m.setStatus(OutboxMessageStatus.Error.name());
                                        outboxMessageEntityRepository.save(m);
                                        return;
                                    }
                                    try {
                                        // Dispatch BEFORE marking as Sent: a crash in between redelivers the
                                        // message (at-least-once) and handlers deduplicate; marking first
                                        // would lose the message forever on a crash after the save.
                                        processDomainEventUseCase.handle(new ProcessDomainEventCommand(event));
                                        m.setStatus(OutboxMessageStatus.Sent.name());
                                        outboxMessageEntityRepository.save(m);
                                    } catch (Exception e) {
                                        if (io.mateu.workflow.application.services.EventFailures.isRetryable(e)) {
                                            log.error("Failed to process embedded outbox message {}, will retry next cycle", m.getId(), e);
                                        } else {
                                            // Never going to succeed. Park it as Error — the same
                                            // resting place an undeserializable message gets, and
                                            // the embedded equivalent of a dead-letter topic:
                                            // visible in the table, replayable by putting it back
                                            // to Pending. Left Pending it would be retried every
                                            // cycle, for ever.
                                            log.error("Embedded outbox message {} cannot be processed, marking as Error", m.getId(), e);
                                            m.setStatus(OutboxMessageStatus.Error.name());
                                            outboxMessageEntityRepository.save(m);
                                        }
                                    }
                                });
                            } finally {
                                dbLockDialect.unlock(con, LOCK_ID);
                            }
                            return null;
                        });
                    } catch (Throwable e) {
                        log.error("Error processing embedded outbox messages", e);
                    }
                    // Same as the Kafka relay: woken by this pod's own writes, polling only as
                    // the fallback for rows another pod wrote.
                    outboxSignal.awaitWork(pollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "embedded-outbox-relay");
        thread.setDaemon(true);
        thread.start();
    }
}
