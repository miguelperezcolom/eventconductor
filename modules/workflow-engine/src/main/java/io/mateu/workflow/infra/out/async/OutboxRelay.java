package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    final OutboxMessageEntityRepository outboxMessageEntityRepository;
    final StreamBridge streamBridge;

    @PostConstruct
    public void iterate() {
        new Thread(() -> {
            try {
            while (true) {
                try {
                    outboxMessageEntityRepository.findByStatus(OutboxMessageStatus.Pending.name()).forEach(m -> {
                        log.info("Relaying outbox message {}", m.getId());
                        // Mark as Sent BEFORE sending so a crash after the send doesn't cause a duplicate.
                        // If the send fails we revert to Pending so the message is retried next cycle.
                        m.setStatus(OutboxMessageStatus.Sent.name());
                        outboxMessageEntityRepository.save(m);
                        try {
                            streamBridge.send("outbox", pojoFromJson(m.getPayload(), Class.forName(m.getMessageType())));
                        } catch (Exception e) {
                            log.error("Failed to relay outbox message {}, reverting to Pending", m.getId(), e);
                            m.setStatus(OutboxMessageStatus.Pending.name());
                            outboxMessageEntityRepository.save(m);
                        }
                    });
                } catch (Throwable e) {
                    log.error("Error relaying outbox messages", e);
                }
                    Thread.sleep(5000);
            }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

}
