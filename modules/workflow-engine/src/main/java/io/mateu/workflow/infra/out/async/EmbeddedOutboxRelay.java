package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.usecases.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.application.usecases.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded")
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class EmbeddedOutboxRelay {

    final OutboxMessageEntityRepository outboxMessageEntityRepository;
    final ProcessDomainEventUseCase processDomainEventUseCase;

    @PostConstruct
    public void iterate() {
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        outboxMessageEntityRepository.findByStatus(OutboxMessageStatus.Pending.name()).forEach(m -> {
                            log.info("Processing embedded outbox message {}", m.getId());
                            // Mark as Sent BEFORE processing so a crash after dispatch doesn't cause a duplicate.
                            // If processing fails we revert to Pending so the message is retried next cycle.
                            m.setStatus(OutboxMessageStatus.Sent.name());
                            outboxMessageEntityRepository.save(m);
                            try {
                                DomainEvent event = (DomainEvent) pojoFromJson(m.getPayload(), Class.forName(m.getMessageType()));
                                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event));
                            } catch (Exception e) {
                                log.error("Failed to process embedded outbox message {}, reverting to Pending", m.getId(), e);
                                m.setStatus(OutboxMessageStatus.Pending.name());
                                outboxMessageEntityRepository.save(m);
                            }
                        });
                    } catch (Throwable e) {
                        log.error("Error processing embedded outbox messages", e);
                    }
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }
}
