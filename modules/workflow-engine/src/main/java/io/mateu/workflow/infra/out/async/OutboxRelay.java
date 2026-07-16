package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka", matchIfMissing = true)
@ConditionalOnProperty(name = "workflow.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    // Must differ from WorkflowOrchestrator.LOCK_ID (123456789L)
    private static final long LOCK_ID = 111222333L;

    final OutboxMessageEntityRepository outboxMessageEntityRepository;
    final StreamBridge streamBridge;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox-poll-interval-ms:5000}")
    long pollIntervalMs;

    @PostConstruct
    public void iterate() {
        var thread = new Thread(() -> {
            try {
                while (true) {
                    try {
                        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                            if (!dbLockDialect.tryLock(con, LOCK_ID)) return null;
                            try {
                                outboxMessageEntityRepository.findByStatus(OutboxMessageStatus.Pending.name()).forEach(m -> {
                                    log.info("Relaying outbox message {}", m.getId());
                                    Object payload;
                                    try {
                                        payload = pojoFromJson(m.getPayload(), OutboxMessages.messageClass(m.getMessageType()));
                                    } catch (Exception e) {
                                        // Poison message: retrying can never succeed, park it as Error.
                                        log.error("Outbox message {} cannot be deserialized, marking as Error", m.getId(), e);
                                        m.setStatus(OutboxMessageStatus.Error.name());
                                        outboxMessageEntityRepository.save(m);
                                        return;
                                    }
                                    try {
                                        // Send BEFORE marking as Sent: a crash in between redelivers the
                                        // message (at-least-once) and consumers deduplicate; marking first
                                        // would lose the message forever on a crash after the save.
                                        streamBridge.send("outbox", payload);
                                        m.setStatus(OutboxMessageStatus.Sent.name());
                                        outboxMessageEntityRepository.save(m);
                                    } catch (Exception e) {
                                        log.error("Failed to relay outbox message {}, will retry next cycle", m.getId(), e);
                                    }
                                });
                            } finally {
                                dbLockDialect.unlock(con, LOCK_ID);
                            }
                            return null;
                        });
                    } catch (Throwable e) {
                        log.error("Error relaying outbox messages", e);
                    }
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "outbox-relay");
        thread.setDaemon(true);
        thread.start();
    }

}
