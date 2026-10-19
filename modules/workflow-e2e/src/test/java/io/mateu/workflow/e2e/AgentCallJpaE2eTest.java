package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.ScriptedModel;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.mateu.workflow.e2e.support.ScriptedModel.call;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AGENT_CALL on embedded + JPA: the turns and tool calls are rows of their own, driven through the
 * outbox and the relay, asynchronously — the loop's state is nothing but those rows.
 */
class AgentCallJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired CancelProcessUseCase cancelProcessUseCase;

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }

    private String processVariable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.domain.aggregates.Variable::value).findFirst().orElse(null);
    }

    private List<StepExecution> children(String businessKey) {
        return steps(businessKey).stream().filter(StepExecution::isIteration)
                .sorted(Comparator.comparing(StepExecution::getItemIndex)).toList();
    }

    @Test
    void parallelToolCallsAndTheFinalAnswerThroughTheDatabase() {
        var model = new ScriptedModel()
                .thenCall(call("lookupOrder", Map.of("orderId", "1")), call("lookupOrder", Map.of("orderId", "2")))
                .thenAnswer("{\"decision\":\"both\"}");
        worker.on("triage", model);
        worker.on("lookup-order", (req, cb, n) -> cb.complete(List.of(TestWorker.var("order", "O" + var(req, "orderId")))));

        createProcess("agent-support", "jag-1", new Variable("customerId", "C"), new Variable("message", "m"));

        awaitStatus("jag-1", ProcessStatus.COMPLETED);
        assertThat(processVariable("jag-1", "decision")).isEqualTo("both");
        assertThat(children("jag-1")).extracting(StepExecution::getStepId)
                .containsExactly("triage", "lookup-order", "lookup-order", "triage");
        assertThat(model.lastToolResults()).extracting(r -> r.path("content").asText())
                .containsExactly("{\"order\":\"O1\"}", "{\"order\":\"O2\"}");
        var agent = steps("jag-1").stream().filter(s -> "triage".equals(s.getStepId()) && !s.isIteration()).findFirst().orElseThrow();
        assertThat(agent.getOutputJson()).contains("\"state\":\"answered\"");
    }

    @Test
    void aFailureAfterTheAgentCompensatesItsToolCallsThroughTheDatabase() {
        worker.on("triage", new ScriptedModel()
                .thenCall(call("openRefund", Map.of("orderId", "A", "amount", 1)), call("openRefund", Map.of("orderId", "B", "amount", 2)))
                .thenAnswer("{\"decision\":\"refunded\"}"));
        worker.on("open-refund", (req, cb, n) -> cb.complete(List.of(TestWorker.var("refundId", "RF-" + var(req, "orderId")))));
        var cancelled = new CopyOnWriteArrayList<String>();
        worker.on("cancel-refund", (req, cb, n) -> {
            cancelled.add(var(req, "refundId"));
            cb.complete();
        });
        worker.on("confirm", TestWorker.failWith("[non-retryable] no"));

        createProcess("agent-support", "jag-saga", new Variable("customerId", "C"), new Variable("message", "m"));

        awaitStatus("jag-saga", ProcessStatus.COMPENSATED);
        assertThat(cancelled).containsExactlyInAnyOrder("RF-A", "RF-B");
    }

    @Test
    void cancellingMidLoopCancelsTheToolAtItsWorker() {
        worker.on("triage", new ScriptedModel().thenCall(call("lookupOrder", Map.of("orderId", "1"))));
        worker.on("lookup-order", TestWorker.deferForever());

        createProcess("agent-support", "jag-cancel", new Variable("customerId", "C"), new Variable("message", "m"));
        await().atMost(TIMEOUT).until(() -> worker.invocationsOf("lookup-order") == 1);
        cancelProcessUseCase.handle(new CancelProcessCommand(process("jag-cancel").getId()));

        awaitStatus("jag-cancel", ProcessStatus.CANCELLED);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(children("jag-cancel"))
                .allSatisfy(c -> assertThat(c.getStatus()).isIn(StepExecutionStatus.CANCELLED, StepExecutionStatus.COMPLETED)));
        assertThat(worker.invocationsOf("triage")).isEqualTo(1);
    }
}
