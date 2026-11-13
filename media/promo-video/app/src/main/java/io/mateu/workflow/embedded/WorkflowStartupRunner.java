package io.mateu.workflow.embedded;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Starts the bookings the promo video looks at (set promo.bookings=false to start none). */
@ConditionalOnProperty(name = "promo.app", havingValue = "true", matchIfMissing = true)
@Component
@RequiredArgsConstructor
public class WorkflowStartupRunner implements ApplicationRunner {

    final ProcessUpstreamEventUseCase upstream;

    @Override
    public void run(ApplicationArguments args) {
        if (!Boolean.parseBoolean(System.getProperty("promo.bookings", "true"))) {
            return;
        }
        String key = System.getProperty("promo.business-key", "BK-4711");
        upstream.handle(new ProcessUpstreamEventCommand(new ProcessCreationRequested(
                "hotel-booking", key,
                List.of(new Variable("rooms", "[{\"number\":101},{\"number\":102},{\"number\":103}]")))));
    }
}
