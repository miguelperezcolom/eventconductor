package io.mateu.workflow.application.services;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.PauseProcessRequested;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import io.mateu.workflow.dtos.events.integration.ResumeProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryStepExecutionRequested;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskResourceCreated;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.input.InputLimits;

/**
 * The size check every event that arrives from outside the engine passes before a handler sees it.
 *
 * <p>The upstream channel is the engine's front door: a process creation from a client, a message from
 * a webhook or an MCP tool, a worker reporting back on the task it was given. Everything on it was
 * composed by somebody who is not the engine, and until this existed nothing asked how big any of it
 * was — {@code InputLimits} explains what that costs and where the numbers come from.
 *
 * <p><b>One chokepoint rather than a check per handler</b>, because the property being defended is
 * "nothing enormous gets in", and a property enforced in eleven places is a property that holds in ten
 * of them after the next event type is added. It is checked in
 * {@code ProcessUpstreamEventUseCase}, which is where every channel converges: the {@code upstream}
 * and {@code messages} Kafka topics, the embedded publisher that stands in for them, and therefore the
 * REST endpoint and the MCP tools that publish through either.
 *
 * <p><b>What happens to a refusal is decided by the caller, and that is the point of throwing.</b>
 * Under Kafka the consumer parks the event on the dead-letter destination and moves on, exactly as it
 * does for any other event that will fail the same way forever — the offending input is kept where
 * someone can look at it and replay it, and the partition is not stalled behind it. Embedded, it
 * reaches whoever called in. The REST controller does not rely on either: it runs the same checks at
 * the edge so a caller gets 400 with the reason, rather than 202 and a silent dead letter.
 *
 * <p><b>Only the upstream path.</b> Events the engine generates for itself — a child process created
 * by a PROCESS step, a status change relayed through the outbox — travel the outbox channel and are
 * not checked here. That is deliberate: their contents are the engine's own accumulated state rather
 * than anybody's input, so a process that has legitimately grown large must not find itself
 * dead-lettered halfway through by a limit meant for its callers.
 */
public final class UpstreamInputGuard {

    private UpstreamInputGuard() {
    }

    /**
     * Rejects an event carrying more than {@link InputLimits} allows.
     *
     * <p>Shapes with nothing untrusted in them — the scheduler's own timer and timeout checks, a rule
     * publication from the rule engine — fall through unchecked. Silence rather than a guess: an
     * unrecognised event is left exactly as it was, and the day a new one arrives with a payload on it,
     * the answer is a case here.
     *
     * @throws InputLimits.InputRejectedException if a limit is exceeded
     */
    public static void check(DomainEvent event) {
        switch (event) {
            case ProcessCreationRequested e -> {
                InputLimits.checkIdentifier(e.workflowDefinitionId(), "workflowDefinitionId");
                InputLimits.checkIdentifier(e.businessKey(), "businessKey");
                InputLimits.checkIdentifier(e.parentStepExecutionId(), "parentStepExecutionId");
                InputLimits.checkVariables(e.variables(), "a process creation");
            }
            case MessageReceived e -> {
                InputLimits.checkIdentifier(e.messageName(), "messageName");
                InputLimits.checkIdentifier(e.correlationKey(), "correlationKey");
                InputLimits.checkVariables(e.variables(), "message '" + e.messageName() + "'");
            }
            case TaskStatusChanged e -> {
                InputLimits.checkIdentifier(e.taskExecutionId(), "taskExecutionId");
                InputLimits.checkIdentifier(e.processId(), "processId");
                InputLimits.checkVariables(e.variables(), "a worker reply");
            }
            case TaskLogEmitted e -> {
                InputLimits.checkIdentifier(e.taskExecutionId(), "taskExecutionId");
                InputLimits.checkText(e.message(), "a task log message");
            }
            case StepsInjected e -> {
                InputLimits.checkIdentifier(e.taskExecutionId(), "taskExecutionId");
                InputLimits.checkIdentifier(e.processId(), "processId");
                InputLimits.checkText(e.stepsJson(), "an injected steps document");
            }
            case TaskResourceCreated e -> {
                InputLimits.checkIdentifier(e.taskExecutionId(), "taskExecutionId");
                InputLimits.checkIdentifier(e.resourceId(), "resourceId");
                InputLimits.checkIdentifier(e.resourceType(), "resourceType");
                InputLimits.checkIdentifier(e.resourceName(), "resourceName");
                // The url column is TEXT, so this is about memory rather than about the column.
                InputLimits.checkText(e.resourceUrl(), "a task resource url");
            }
            case TaskCancellationRequested e -> InputLimits.checkIdentifier(e.taskId(), "taskId");
            case RetryProcessRequested e -> InputLimits.checkIdentifier(e.processId(), "processId");
            case RestartProcessRequested e -> InputLimits.checkIdentifier(e.processId(), "processId");
            case PauseProcessRequested e -> InputLimits.checkIdentifier(e.processId(), "processId");
            case ResumeProcessRequested e -> InputLimits.checkIdentifier(e.processId(), "processId");
            case RetryStepExecutionRequested e -> {
                InputLimits.checkIdentifier(e.stepExecutionId(), "stepExecutionId");
                InputLimits.checkIdentifier(e.processId(), "processId");
            }
            case ProcessCancellationRequested e -> {
                InputLimits.checkIdentifier(e.businessKey(), "businessKey");
                InputLimits.checkIdentifier(e.processId(), "processId");
            }
            default -> {
            }
        }
    }
}
