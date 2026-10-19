package io.mateu.workflow.uie2e.support;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;

import java.util.List;

/**
 * The single worker every ACTION is dispatched to.
 *
 * <p>Behaviour is keyed on the step id and fixed rather than programmable: these tests are about
 * what an operator sees and does in the browser, so the engine underneath should behave the same
 * way every run. A step named {@code charge} fails, which is what gives the UI a process that
 * rolls back — a compensation drawn amber, an Errors tab with a reason in it, and a process that
 * ends COMPENSATED instead of COMPLETED.
 */
public class UiTestWorker implements EmbeddedTaskExecutor {

    /** The step whose failure drives the saga rollback the UI tests read. */
    public static final String FAILING_STEP = "charge";

    private final UpdateStepExecutionUseCase updateStepExecution;

    public UiTestWorker(UpdateStepExecutionUseCase updateStepExecution) {
        this.updateStepExecution = updateStepExecution;
    }

    @Override
    public void execute(TaskExecutionRequested request) {
        if ("agent-turn@1".equals(request.taskId())) {
            answerAsAModel(request);
            return;
        }
        if (FAILING_STEP.equals(request.stepId())) {
            report(request, StepExecutionStatus.ERROR, "The card was declined");
        } else {
            report(request, StepExecutionStatus.COMPLETED, "Done: " + request.stepId());
        }
    }

    /**
     * The model of an AGENT_CALL, scripted: its first turn calls the first tool, the next one answers —
     * so the UI has an agent with a turn, a tool call and an answer to show. Never a provider.
     */
    private void answerAsAModel(TaskExecutionRequested request) {
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var agent = json.readTree(request.variables().stream().filter(v -> "__agent".equals(v.name()))
                    .findFirst().orElseThrow().value());
            var answer = agent.path("messages").size() == 1
                    ? java.util.Map.of("text", "Let me look it up.", "toolCalls", List.of(java.util.Map.of("id", "call_1",
                            "name", agent.path("tools").get(0).path("name").asText(), "arguments", "{\"orderId\":\"O-1\"}")),
                            "usage", java.util.Map.of("inputTokens", 40, "outputTokens", 12))
                    : java.util.Map.of("text", "The order is on its way.", "toolCalls", List.of(),
                            "usage", java.util.Map.of("inputTokens", 60, "outputTokens", 9));
            updateStepExecution.handle(new UpdateStepExecutionCommand(request.taskExecutionId(),
                    List.of(new io.mateu.workflow.domain.aggregates.Variable("__agentAnswer", json.writeValueAsString(answer))),
                    "Model turn answered", StepExecutionStatus.COMPLETED));
        } catch (Exception e) {
            report(request, StepExecutionStatus.ERROR, "The scripted model could not answer: " + e.getMessage());
        }
    }

    private void report(TaskExecutionRequested request, StepExecutionStatus status, String log) {
        updateStepExecution.handle(new UpdateStepExecutionCommand(
                request.taskExecutionId(), List.of(), log, status));
    }
}
