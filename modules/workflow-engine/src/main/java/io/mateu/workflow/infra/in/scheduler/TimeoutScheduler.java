package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksCommand;
import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksUseCase;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimeoutScheduler {

    final StepExecutionEntityRepository stepExecutionEntityRepository;
    final StreamBridge streamBridge;

    @PostConstruct
    public void start() {
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        stepExecutionEntityRepository.findAll().forEach(se -> {
                           if (StepExecutionStatus.RUNNING.name().equals(se.getStatus())) {
                               var step = pojoFromJson(se.getStepJson(), Step.class);
                               if (step.timeout() > 0) {
                                   streamBridge.send("upstream", new TimeoutCheckRequested(se.getProcessId()));
                               }
                           }
                        });
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
