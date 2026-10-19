package io.mateu.workflow.application.usecases.correlatemessage;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.domain.services.InboundEvents;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepCommand;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

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
 *
 * <p>A message made from a domain event (a {@code WAIT_FOR_MESSAGE}'s {@code onEvent}) carries the
 * event's origin. Its receipt — what makes a redelivery of the event a duplicate — is written here,
 * once a waiting step actually took the message, not when the event was received: an event that
 * arrived before the step was waiting is dropped without a receipt, so its redelivery (the documented
 * remedy) is correlated again instead of being refused as a duplicate. A redelivery of an event a step
 * already took is dropped here, before it could complete a later wait on the same message and key.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CorrelateMessageUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final CompleteMessageStepHandler completeMessageStepHandler;
    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;
    final io.mateu.workflow.application.services.ProcessTrace processTrace;
    final io.mateu.workflow.application.services.messagerouting.MessageRoutingMetrics messageRoutingMetrics;
    final io.mateu.workflow.application.services.messagerouting.WaitingMessageFilter waitingMessageFilter;
    final EventReceiptRepository eventReceiptRepository;

    public void handle(CorrelateMessageCommand command) {
        // Layer 3: a broadcast reaches every shard, but the per-shard filter can say — without a
        // query — that no step here is waiting for this pair, so the shard drops it. No false
        // negatives: a pair a step here is waiting for is always in the filter (or the filter is
        // disabled/not-ready and says "maybe"), so this only ever saves the query below.
        if (!waitingMessageFilter.mightBeWaitingFor(command.messageName(), command.correlationKey())) {
            messageRoutingMetrics.correlationQuerySkipped();
            return;
        }
        // The query whose S×M cost message routing exists to reduce; counted per shard so the
        // effect is measurable (a routed message reaches only the shard(s) that can match it).
        messageRoutingMetrics.correlationQuery();
        // Correlation is the one piece of engine work that does not know whose process it is until
        // it has done it: a message arrives naming a message and a key, and which step — if any —
        // is waiting for that pair is precisely what the lookup answers. So the lookup itself runs
        // untraced, and each match is traced inside the trace of the process it turned out to
        // belong to. A span around the lookup would be a root of its own with nothing to attach it
        // to, which is what every span in the engine used to be.
        var receiver = "wait:" + command.messageName() + "/" + command.correlationKey();
        var receiptKey = command.origin() == null ? null : InboundEvents.receiptKey(receiver, asEvent(command.origin()));
        if (receiptKey != null && eventReceiptRepository.exists(receiptKey)) {
            log.info("Event {} from {} was already taken by a step waiting for message '{}' with key '{}': ignored",
                    command.origin().id(), command.origin().source(), command.messageName(), command.correlationKey());
            return;
        }
        var matched = stepExecutionRepository.findWaitingForMessage(
                command.messageName(), command.correlationKey());
        if (matched.isEmpty()) {
            log.warn("Message '{}' with correlation key '{}' matched no waiting step — ignored (messages are not buffered)",
                    command.messageName(), command.correlationKey());
            return;
        }
        var takenBy = new java.util.concurrent.atomic.AtomicReference<String>();
        matched.forEach(se -> workflowTracing.continuing(
                processTrace.contextFor(se.getProcessId()),
                "eventconductor.correlate-message",
                java.util.Map.of("eventconductor.process.id", String.valueOf(se.getProcessId()),
                        "eventconductor.message.name", String.valueOf(command.messageName())),
                () -> {
                    if (completeMessageStepHandler.handle(new CompleteMessageStepCommand(
                            se.id(), command.messageName(), command.correlationKey(), command.variables()))) {
                        takenBy.compareAndSet(null, String.valueOf(se.getWorkflowDefinitionId()));
                    }
                }));
        if (receiptKey != null && takenBy.get() != null) {
            var origin = command.origin();
            eventReceiptRepository.save(new EventReceipt(receiptKey, receiver, EventReceipt.Kind.WAIT,
                    origin.destination(), origin.type(), origin.source(), origin.id(), EventReceipt.Outcome.DELIVERED,
                    takenBy.get(), null, null, command.messageName(), command.correlationKey(), null, EngineClock.instant()));
        }
    }

    private static InboundEvent asEvent(MessageReceived.EventOrigin origin) {
        return new InboundEvent(origin.destination(), origin.id(), origin.source(), origin.type(), null, null, null,
                null, null, null);
    }

}
