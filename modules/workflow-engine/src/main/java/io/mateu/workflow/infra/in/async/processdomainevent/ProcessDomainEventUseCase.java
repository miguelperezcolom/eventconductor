package io.mateu.workflow.infra.in.async.processdomainevent;

import io.mateu.workflow.ddd.DomainEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class ProcessDomainEventUseCase {

    final List<DomainEventHandler> handlers;
    final io.mateu.workflow.application.services.VariableProtection protection;

    public ProcessDomainEventUseCase(List<DomainEventHandler> handlers) {
        this(handlers, io.mateu.workflow.application.services.VariableProtection.none());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProcessDomainEventUseCase(List<DomainEventHandler> handlers,
                                     io.mateu.workflow.application.services.VariableProtection protection) {
        this.handlers = handlers;
        this.protection = protection;
    }

    public void handle(ProcessDomainEventCommand command) {
        // Described, not printed: the event may carry sensitive values in clear (it is in the engine).
        if (log.isInfoEnabled()) {
            log.info("Processing domain event: {}", protection.describe(command.event()));
        }
        handlers.stream()
                .filter(h -> h.canHandle(command.event()))
                // Failures are not caught here. Swallowing them was how an event the engine
                // could not process disappeared: logged once, never retried, never parked,
                // invisible to anyone not reading that log. Whoever delivered the event decides
                // what to do with the failure — retry it if the environment was at fault, park
                // it on the dead-letter destination if the event itself is defective — and that
                // decision cannot be made here, because it depends on how the event arrived.
                .forEach(h -> h.handle(command.event()));
    }

}
