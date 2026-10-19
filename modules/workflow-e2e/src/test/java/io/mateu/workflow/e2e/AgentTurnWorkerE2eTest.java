package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.worker.api.Cancellations;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskRegistry;
import io.mateu.workflow.worker.api.TaskReplySink;
import io.mateu.workflow.worker.llm.AgentTurnHandler;
import io.mateu.workflow.worker.llm.LlmCallMetrics;
import io.mateu.workflow.worker.llm.LlmWorkerAutoConfiguration;
import io.mateu.workflow.worker.llm.LlmWorkerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT_CALL through the real built-in agent-turn task (worker-llm, run by the worker runtime's
 * TaskDispatcher as worker-embedded does) over a stub model: the model's tool_use becomes a real tool
 * step execution, its result goes back as a tool response, and the final JSON answer lands in the process.
 */
class AgentTurnWorkerE2eTest extends AbstractE2eTest {

    final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    SimpleMeterRegistry meters;

    @BeforeEach
    void wireTheAgentTurnTask() {
        var properties = new LlmWorkerProperties();
        var claude = new LlmWorkerProperties.Connection();
        claude.setModel("claude-opus-5-5");
        claude.setApiKey("never-used");
        properties.getConnections().put("claude", claude);
        ChatModel model = prompt -> {
            prompts.add(prompt);
            var message = prompts.size() == 1
                    ? AssistantMessage.builder().content("Looking it up.").toolCalls(List.of(
                            new AssistantMessage.ToolCall("toolu_1", "function", "lookupOrder", "{\"orderId\":\"O-5\"}"))).build()
                    : new AssistantMessage("{\"decision\":\"wait\"}");
            return new ChatResponse(List.of(new Generation(message)),
                    ChatResponseMetadata.builder().model("claude-opus-5-5").usage(new DefaultUsage(100, 10, 110, null)).build());
        };
        meters = new SimpleMeterRegistry();
        var handler = new AgentTurnHandler(properties, (name, connection) -> model, new LlmCallMetrics(meters));
        var sink = new TaskReplySink() {
            public void running(TaskExecutionRequested task) { }
            public void completed(TaskExecutionRequested task, List<Variable> variables) {
                updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(task.taskExecutionId(),
                        variables.stream().map(v -> new io.mateu.workflow.domain.aggregates.Variable(v.name(), v.value())).toList(),
                        "", StepExecutionStatus.COMPLETED));
            }
            public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {
                updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(task.taskExecutionId(),
                        List.of(), reason, StepExecutionStatus.ERROR));
            }
        };
        io.mateu.workflow.worker.api.TaskRegistration<?, ?> registration =
                new LlmWorkerAutoConfiguration().agentTurnTaskRegistration(handler, properties);
        var dispatcher = new TaskDispatcher(new TaskRegistry(List.of(registration)),
                sink, Cancellations.NONE, new ObjectMapper(), true);
        worker.on("triage", (request, callback, invocation) -> dispatcher.dispatch(request));
        worker.on("lookup-order", TestWorker.succeed(TestWorker.var("eta", "tomorrow")));
    }

    @Test
    void theRealTurnTaskDrivesTheLoopToTheAnswer() {
        createProcess("agent-support", "agw-1", new Variable("customerId", "C-1"), new Variable("message", "late"));

        var process = process("agw-1");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process.getVariables()).anyMatch(v -> v.name().equals("decision") && v.value().equals("wait"));
        assertThat(prompts).hasSize(2);
        var second = prompts.get(1).getInstructions();
        assertThat(second).extracting(m -> m.getMessageType())
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
        assertThat(((ToolResponseMessage) second.get(3)).getResponses().getFirst().responseData()).isEqualTo("{\"eta\":\"tomorrow\"}");
        assertThat(worker.received()).anyMatch(r -> r.stepId().equals("lookup-order")
                && r.variables().stream().anyMatch(v -> v.name().equals("orderId") && v.value().equals("O-5")));
        assertThat(meters.find("eventconductor.llm.call").tags("step", "triage", "outcome", "success").timer().count()).isEqualTo(2);
    }
}
