package io.mateu.workflow.embeddedgit;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@Slf4j
public class WorkersConfig {

    @Bean
    public EmbeddedTaskExecutor defaultWorker(UpdateStepExecutionUseCase updateStepExecution) {
        return request -> {
            log.info("Executing step '{}' (stepId: {}, process: {})",
                    request.taskExecutionId(), request.stepId(), request.processId());
            updateStepExecution.handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(),
                    List.of(),
                    "",
                    StepExecutionStatus.COMPLETED
            ));
        };
    }
}
