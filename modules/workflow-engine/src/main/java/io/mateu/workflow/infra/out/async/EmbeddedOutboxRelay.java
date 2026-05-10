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
                            try {
                                DomainEvent event = (DomainEvent) pojoFromJson(m.getPayload(), Class.forName(m.getMessageType()));
                                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event));
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                            m.setStatus(OutboxMessageStatus.Sent.name());
                            outboxMessageEntityRepository.save(m);
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
