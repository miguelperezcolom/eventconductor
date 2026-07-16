package io.mateu.workflow.application.usecases.completetask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompleteTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final StreamBridge streamBridge;

    public void handle(CompleteTaskCommand command) {
        var execution = formExecutionRepository.findById(command.taskId()).orElseThrow();
        execution = execution
                .withValues(command.values())
                .withStatus(FormExecutionStatus.COMPLETED);
        formExecutionRepository.save(execution);

        streamBridge.send("upstream", new TaskLogEmitted(
                execution.stepExecutionId(),
                MessageType.Info,
                "form " + execution.formId() + " completed by " + execution.userId()));

        streamBridge.send("upstream", new TaskStatusChanged(
                execution.stepExecutionId(),
                TaskStatus.COMPLETED,
                execution.values().stream().map(v -> new Variable(v.name(), v.value())).toList()));
    }

}
