package io.mateu.workflow.application.usecases.processdomainevent;

import io.mateu.workflow.ddd.DomainEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessDomainEventUseCase {

    final List<DomainEventHandler> handlers;

    public void handle(ProcessDomainEventCommand command) {
        log.info("Processing domain event: " + command.event());
        handlers.stream()
                .filter(h -> h.canHandle(command.event()))
                .forEach(h -> {
                    try {
                        h.handle(command.event());
                    } catch (Throwable e) {
                        log.error("Error processing domain event", e);
                    }
                });
    }

}
