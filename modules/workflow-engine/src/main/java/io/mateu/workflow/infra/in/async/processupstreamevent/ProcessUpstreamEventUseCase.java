package io.mateu.workflow.infra.in.async.processupstreamevent;

import io.mateu.workflow.application.services.UpstreamInputGuard;
import io.mateu.workflow.ddd.DomainEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessUpstreamEventUseCase {

    final List<DomainEventHandler> handlers;

    public void handle(ProcessUpstreamEventCommand command) {
        log.info("Processing domain event: " + command.event());
        // Every channel from outside the engine converges here, so this is where "nothing enormous
        // gets in" is enforced — once, before any handler has allocated anything on the event's
        // behalf. A refusal is thrown for the same reason a handler failure is: what to do about it
        // depends on how the event arrived, and only the caller knows. See UpstreamInputGuard.
        UpstreamInputGuard.check(command.event());
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
