package io.mateu.workflow.application.usecases.correlatemessage;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepCommand;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepHandler;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * What matching <em>means</em> now lives on the step execution (which arms its subscription) and
 * in the repository query (which resolves it) — see {@code StepExecutionMessageSubscriptionTest}
 * and {@code InMemoryStepExecutionRepositoryTest}. What is left here is the use case's own job:
 * ask for the subscribers of this exact message and hand each one to the completion handler.
 */
@ExtendWith(MockitoExtension.class)
class CorrelateMessageUseCaseTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock CompleteMessageStepHandler completeMessageStepHandler;

    @InjectMocks CorrelateMessageUseCase useCase;

    private StepExecution pending(String id) {
        return StepExecution.builder()
                .id(id).processId("p-1")
                .status(StepExecutionStatus.PENDING)
                .build();
    }

    @Test
    void asksForTheSubscribersOfTheIncomingMessage() {
        // The lookup must carry both halves of the subscription: resolving it in the query is
        // the whole point — a walk over every waiting step is what this replaced.
        useCase.handle(new CorrelateMessageCommand("payment-received", "bk-1", List.of()));

        verify(stepExecutionRepository).findWaitingForMessage("payment-received", "bk-1");
        verify(stepExecutionRepository, never()).findPendingOrRunning();
    }

    @Test
    void handsEveryMatchedStepToTheCompletionHandler() {
        var variables = List.of(new Variable("amount", "42"));
        when(stepExecutionRepository.findWaitingForMessage("payment-received", "bk-1"))
                .thenReturn(List.of(pending("se-1"), pending("se-2")));

        useCase.handle(new CorrelateMessageCommand("payment-received", "bk-1", variables));

        verify(completeMessageStepHandler).handle(
                new CompleteMessageStepCommand("se-1", "payment-received", "bk-1", variables));
        verify(completeMessageStepHandler).handle(
                new CompleteMessageStepCommand("se-2", "payment-received", "bk-1", variables));
    }

    @Test
    void ignoresMessageWhenNoStepIsSubscribed() {
        // Messages are not buffered: an unmatched one is dropped, not held for a later waiter.
        useCase.handle(new CorrelateMessageCommand("payment-received", "bk-1", List.of()));

        verify(completeMessageStepHandler, never()).handle(any());
    }
}
