package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.out.StepExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpdateStepExecutionUseCase {

    final StepExecutionRepository repository;

    public void handle(UpdateStepExecutionCommand command) {
        var execution = repository.findById(command.stepId()).orElseThrow();
        execution.updateStatus(command.status());
        repository.save(execution);
    }

}
