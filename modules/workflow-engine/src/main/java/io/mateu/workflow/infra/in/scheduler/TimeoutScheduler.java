package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksCommand;
import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksUseCase;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimeoutScheduler {

    final StreamBridge streamBridge;

    @PostConstruct
    public void start() {
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        streamBridge.send("upstream", new TimeoutCheckRequested());
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
