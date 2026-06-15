package io.mateu.workflow.embeddedmvc;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GreetWorker implements EmbeddedTaskExecutor {

    private final UpdateStepExecutionUseCase updateStepExecution;

    @Override
    public void execute(TaskExecutionRequested request) {
        String name = request.variables().stream()
                .filter(v -> "name".equals(v.name()))
                .map(io.mateu.workflow.dtos.Variable::value)
                .findFirst().orElse("World");

        System.out.println("Hello, " + name + "!");

        updateStepExecution.handle(new UpdateStepExecutionCommand(
                request.taskExecutionId(),
                List.of(new Variable("greeting", "Hello, " + name + "!")),
                "",
                StepExecutionStatus.COMPLETED
        ));
    }
}
