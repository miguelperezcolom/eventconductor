package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
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
                        log.info("Relaying outbox message " + m.getId());
                        try {
                            streamBridge.send("outbox", pojoFromJson(m.getPayload(), Class.forName(m.getMessageType())));
                        } catch (ClassNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                        m.setStatus(OutboxMessageStatus.Sent.name());
                        outboxMessageEntityRepository.save(m);
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
