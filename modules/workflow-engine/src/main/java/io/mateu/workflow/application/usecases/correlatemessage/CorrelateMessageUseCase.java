package io.mateu.workflow.application.usecases.correlatemessage;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepCommand;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepHandler;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Correlates an incoming {@code MessageReceived} against MESSAGE steps waiting for it.
 * A step matches when it is PENDING on the same messageName and the process correlation
 * key (businessKey by default, or the step's correlationExpression) equals the message's
 * correlation key. Matches are completed by {@link CompleteMessageStepHandler} under the
 * process lock. A message that matches no waiting step is ignored (logged, not buffered):
 * upstream delivery is at-least-once, so the sender simply retries once the process is
 * waiting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CorrelateMessageUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final CompleteMessageStepHandler completeMessageStepHandler;

    public void handle(CorrelateMessageCommand command) {
        var matched = stepExecutionRepository.findPendingOrRunning().stream()
                .filter(se -> StepExecutionStatus.PENDING.equals(se.getStatus()))
                .filter(se -> se.getStartedAt() != null)
                .filter(se -> isWaitingFor(se, command))
                .toList();
        if (matched.isEmpty()) {
            log.warn("Message '{}' with correlation key '{}' matched no waiting step — ignored (messages are not buffered)",
                    command.messageName(), command.correlationKey());
            return;
        }
        matched.forEach(se -> completeMessageStepHandler.handle(new CompleteMessageStepCommand(
                se.id(), command.messageName(), command.correlationKey(), command.variables())));
    }

    private boolean isWaitingFor(StepExecution stepExecution, CorrelateMessageCommand command) {
        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
        if (!StepType.MESSAGE.equals(step.type()) || !command.messageName().equals(step.messageName())) {
            return false;
        }
        return processRepository.findById(stepExecution.getProcessId())
                .map(process -> MessageCorrelation.matches(step, process, command.correlationKey()))
                .orElse(false);
    }

}
