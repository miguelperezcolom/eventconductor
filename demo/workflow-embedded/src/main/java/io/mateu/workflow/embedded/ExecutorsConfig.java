package io.mateu.workflow.embedded;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ExecutorsConfig {

    @Bean
    public EmbeddedTaskExecutor greetWorker(UpdateStepExecutionUseCase updateStepExecution) {
        return request -> {
            String name = request.variables().stream()
                    .filter(v -> "name".equals(v.name()))
                    .map(v -> v.value())
                    .findFirst().orElse("World");

            System.out.println("Hello, " + name + "!");

            updateStepExecution.handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(),
                    List.of(new Variable("greeting", "Hello, " + name + "!")),
                    "",
                    StepExecutionStatus.COMPLETED
            ));
        };
    }
}
