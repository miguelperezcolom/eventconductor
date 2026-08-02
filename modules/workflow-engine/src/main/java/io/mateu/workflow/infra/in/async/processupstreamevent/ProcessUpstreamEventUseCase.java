package io.mateu.workflow.infra.in.async.processupstreamevent;

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
        handlers.stream()
                .filter(h -> h.canHandle(command.event()))
                .forEach(h -> {
                    try {
                        h.handle(command.event());
                    } catch (io.mateu.workflow.application.out.ConcurrentProcessAccessException e) {
                        // Not a bad event, just one that lost a race. It has to reach the
                        // consumer so the offset is not committed over work that never
                        // happened — swallowing it here is how such an event disappears.
                        throw e;
                    } catch (Throwable e) {
                        log.error("Error processing domain event", e);
                    }
                });
    }

}
