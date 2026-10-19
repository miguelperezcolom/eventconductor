package io.mateu.workflow.infra.in.async.processupstreamevent;

import io.mateu.workflow.application.services.UpstreamInputGuard;
import io.mateu.workflow.ddd.DomainEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class ProcessUpstreamEventUseCase {

    final List<DomainEventHandler> handlers;
    final io.mateu.workflow.application.services.VariableProtection protection;

    public ProcessUpstreamEventUseCase(List<DomainEventHandler> handlers) {
        this(handlers, io.mateu.workflow.application.services.VariableProtection.none());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProcessUpstreamEventUseCase(List<DomainEventHandler> handlers,
                                       io.mateu.workflow.application.services.VariableProtection protection) {
        this.handlers = handlers;
        this.protection = protection;
    }

    public void handle(ProcessUpstreamEventCommand command) {
        // Logged as it arrived (sealed values stay sealed, plain sensitive ones are masked), then opened:
        // every channel from outside converges here, so this is where sealed variables — from the broker,
        // an HTTP worker, a client that shares the keys — become the plaintext the domain works on.
        if (log.isInfoEnabled()) {
            log.info("Processing domain event: {}", protection.describe(command.event()));
        }
        var event = protection.openEvent(command.event());
        // Every channel from outside the engine converges here, so this is where "nothing enormous
        // gets in" is enforced — once, before any handler has allocated anything on the event's
        // behalf. A refusal is thrown for the same reason a handler failure is: what to do about it
        // depends on how the event arrived, and only the caller knows. See UpstreamInputGuard.
        UpstreamInputGuard.check(event);
        handlers.stream()
                .filter(h -> h.canHandle(event))
                // Failures are not caught here. Swallowing them was how an event the engine
                // could not process disappeared: logged once, never retried, never parked,
                // invisible to anyone not reading that log. Whoever delivered the event decides
                // what to do with the failure — retry it if the environment was at fault, park
                // it on the dead-letter destination if the event itself is defective — and that
                // decision cannot be made here, because it depends on how the event arrived.
                .forEach(h -> h.handle(event));
    }

}
