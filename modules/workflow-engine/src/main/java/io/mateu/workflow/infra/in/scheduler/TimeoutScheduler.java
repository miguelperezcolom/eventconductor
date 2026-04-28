package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksCommand;
import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksUseCase;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimeoutScheduler {

    final TriggerTimeoutChecksUseCase triggerTimeoutChecksUseCase;

    @PostConstruct
    public void start() {
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        triggerTimeoutChecksUseCase.handle(new TriggerTimeoutChecksCommand());
                    } catch (Throwable e) {
                        log.error("Error checking step timeouts", e);
                    }
                    Thread.sleep(10_000);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

}
