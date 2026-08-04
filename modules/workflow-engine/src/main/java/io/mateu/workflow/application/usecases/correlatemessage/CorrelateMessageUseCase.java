package io.mateu.workflow.application.usecases.correlatemessage;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepCommand;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Correlates an incoming {@code MessageReceived} against WAIT_FOR_MESSAGE steps waiting for
 * it. A step matches when it is PENDING on the same messageName and its correlation key (from
 * the step's correlationExpression; businessKey only for pre-rename persisted steps) equals the
 * message's. Both are materialised on the step execution and kept current as the process
 * changes, so this is an indexed lookup rather than a walk over every step waiting anywhere in
 * the engine — with many processes parked on the same message name, that walk was the cost of
 * every single message.
 *
 * <p>Matches are completed by {@link CompleteMessageStepHandler} under the process lock, which
 * re-evaluates the correlation from the live process before acting: this query is the filter,
 * that check is the decision. A message that matches no waiting step is ignored (logged, not
 * buffered): upstream delivery is at-least-once, so the sender simply retries once the process
 * is waiting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CorrelateMessageUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final CompleteMessageStepHandler completeMessageStepHandler;
    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;

    public void handle(CorrelateMessageCommand command) {
        workflowTracing.span("eventconductor.correlate-message",
                java.util.Map.of("messageName", String.valueOf(command.messageName())),
                () -> correlate(command));
    }

    private void correlate(CorrelateMessageCommand command) {
        var matched = stepExecutionRepository.findWaitingForMessage(
                command.messageName(), command.correlationKey());
        if (matched.isEmpty()) {
            log.warn("Message '{}' with correlation key '{}' matched no waiting step — ignored (messages are not buffered)",
                    command.messageName(), command.correlationKey());
            return;
        }
        matched.forEach(se -> completeMessageStepHandler.handle(new CompleteMessageStepCommand(
                se.id(), command.messageName(), command.correlationKey(), command.variables())));
    }

}
