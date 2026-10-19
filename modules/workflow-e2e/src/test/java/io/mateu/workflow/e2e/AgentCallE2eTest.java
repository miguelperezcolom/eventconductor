package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.services.AgentLoop;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.ScriptedModel;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.mateu.workflow.e2e.support.ScriptedModel.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT_CALL end to end, in memory: every model turn and every tool call is a step execution of the
 * agent's; tools run with their own retries and compensation, arguments are validated before anything
 * runs, budgets fail the step, and the final answer is mapped into the process.
 */
class AgentCallE2eTest extends AbstractE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }

    private String processVariable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.domain.aggregates.Variable::value).findFirst().orElse(null);
    }

    private StepExecution agent(String businessKey, String stepId) {
        return steps(businessKey).stream().filter(s -> stepId.equals(s.getStepId()) && !s.isIteration()).findFirst().orElseThrow();
    }

    private List<StepExecution> children(String businessKey, String stepId) {
        var agent = agent(businessKey, stepId);
        return steps(businessKey).stream().filter(s -> agent.getId().equals(s.getParentExecutionId()))
                .sorted(Comparator.comparing(StepExecution::getItemIndex)).toList();
    }

    @Test
    void theModelsToolCallsRunAsStepExecutionsAndItsAnswerIsMapped() {
        var model = new ScriptedModel()
                .thenCall(call("lookupOrder", Map.of("orderId", "O-1")))
                .thenCall(call("openRefund", Map.of("orderId", "O-1", "amount", 20)), call("escalate", Map.of("reason", "angry")))
                .thenAnswer("{\"decision\":\"refund\"}");
        worker.on("triage", model);
        worker.on("lookup-order", (req, cb, n) -> cb.complete(List.of(TestWorker.var("status", "DELIVERED"),
                TestWorker.var("total", "20"), TestWorker.var("seenOrder", var(req, "orderId")))));
        worker.on("open-refund", (req, cb, n) -> cb.complete(List.of(TestWorker.var("refundId", "RF-" + var(req, "orderId")))));
        worker.on("child-work", (req, cb, n) -> cb.complete(List.of(TestWorker.var("handledBy", "ann"))));

        createProcess("agent-support", "ag-1", new Variable("customerId", "C-7"), new Variable("message", "where is my refund"));

        assertThat(process("ag-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(processVariable("ag-1", "decision")).isEqualTo("refund");
        assertThat(processVariable("ag-1", "agentTurns")).isEqualTo("3");
        assertThat(processVariable("ag-1", "agentAnswer")).contains("refund");
        // A tool never writes the process: what it reported went to the model, nowhere else.
        assertThat(processVariable("ag-1", "refundId")).isNull();
        assertThat(processVariable("ag-1", "seenOrder")).isNull();

        var children = children("ag-1", "triage");
        assertThat(children).extracting(StepExecution::getStepId)
                .containsExactly("triage", "lookup-order", "triage", "open-refund", "triage.escalate", "triage");
        assertThat(children).allSatisfy(child -> assertThat(child.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED));
        assertThat(children.get(1).scope()).containsEntry("orderId", "O-1").containsEntry(AgentLoop.TOOL, "lookupOrder");

        // The first request is the goal, rendered; the last one carries both results of the second turn.
        assertThat(model.requests().getFirst().path("messages").get(0).path("content").asText())
                .isEqualTo("Customer C-7 wrote: where is my refund");
        assertThat(model.requests().getFirst().path("tools")).hasSize(3);
        assertThat(model.lastToolResults()).hasSize(2);
        assertThat(model.lastToolResults().get(0).path("content").asText()).contains("RF-O-1");
        assertThat(model.lastToolResults().get(1).path("content").asText()).contains("ann");
        assertThat(model.requests().get(1).toString()).contains("DELIVERED");

        var transcript = agent("ag-1", "triage").getOutputJson();
        assertThat(transcript).contains("\"state\":\"answered\"").contains("\"toolCalls\":3").contains("lookupOrder");
    }

    @Test
    void aCallToAnUnknownToolOrWithInvalidArgumentsIsNotRunAndTheModelIsTold() {
        var model = new ScriptedModel()
                .thenCall(call("lookupOrder", Map.of("order", "O-1")), call("dropTables", Map.of()))
                .thenAnswer("{\"decision\":\"none\"}");
        worker.on("triage", model);

        createProcess("agent-support", "ag-bad", new Variable("customerId", "C"), new Variable("message", "m"));

        assertThat(process("ag-bad").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("lookup-order")).isZero();
        var results = model.lastToolResults();
        assertThat(results.get(0).path("content").asText()).contains("inputSchema").contains("orderId");
        assertThat(results.get(1).path("content").asText()).contains("no tool called 'dropTables'");
    }

    @Test
    void toolCallsOfOneTurnRunUpToTheToolConcurrencyAtOnce() {
        var model = new ScriptedModel()
                .thenCall(call("lookupOrder", Map.of("orderId", "1")), call("lookupOrder", Map.of("orderId", "2")),
                        call("lookupOrder", Map.of("orderId", "3")))
                .thenAnswer("{\"decision\":\"done\"}");
        worker.on("triage", model);
        var parked = new CopyOnWriteArrayList<TaskExecutionRequested>();
        worker.on("lookup-order", (req, cb, n) -> parked.add(req));

        createProcess("agent-support", "ag-par", new Variable("customerId", "C"), new Variable("message", "m"));

        // toolConcurrency 2: two at a worker, the third waits for a slot.
        assertThat(parked).hasSize(2);
        assertThat(children("ag-par", "triage").stream().filter(c -> "lookup-order".equals(c.getStepId())))
                .extracting(StepExecution::getStatus)
                .containsExactlyInAnyOrder(StepExecutionStatus.PENDING, StepExecutionStatus.PENDING, StepExecutionStatus.CREATED);
        complete(parked.get(0), "status", "A");
        assertThat(parked).hasSize(3);
        complete(parked.get(1), "status", "B");
        complete(parked.get(2), "status", "C");

        assertThat(process("ag-par").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(model.lastToolResults()).extracting(r -> r.path("content").asText())
                .containsExactly("{\"status\":\"A\"}", "{\"status\":\"B\"}", "{\"status\":\"C\"}");
    }

    private void complete(TaskExecutionRequested request, String name, String value) {
        updateStepExecutionUseCase.handle(new io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand(
                request.taskExecutionId(), List.of(new io.mateu.workflow.domain.aggregates.Variable(name, value)), "",
                StepExecutionStatus.COMPLETED, false, request.attempt()));
    }

    @Test
    void moreToolCallsThanMaxStepsFailTheAgent() {
        worker.on("triage", new ScriptedModel()
                .thenCall(call("lookupOrder", Map.of("orderId", "1")), call("lookupOrder", Map.of("orderId", "2")),
                        call("lookupOrder", Map.of("orderId", "3")))
                .thenCall(call("lookupOrder", Map.of("orderId", "4")), call("lookupOrder", Map.of("orderId", "5"))));

        createProcess("agent-support", "ag-cap", new Variable("customerId", "C"), new Variable("message", "m"));

        assertThat(process("ag-cap").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(agent("ag-cap", "triage").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(worker.invocationsOf("lookup-order")).isEqualTo(3);
        assertThat(errorsOf("ag-cap")).anyMatch(line -> line.contains("AGENT_MAX_STEPS"));
        assertThat(agent("ag-cap", "triage").getOutputJson()).contains("failed: AGENT_MAX_STEPS");
    }

    @Test
    void aToolThatFailsForGoodFailsTheAgentAfterItsOwnRetries() {
        worker.on("triage", new ScriptedModel().thenCall(call("lookupOrder", Map.of("orderId", "1"))));
        worker.on("lookup-order", TestWorker.failWith("[non-retryable] no such order"));

        createProcess("agent-support", "ag-toolfail", new Variable("customerId", "C"), new Variable("message", "m"));

        assertThat(process("ag-toolfail").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(errorsOf("ag-toolfail")).anyMatch(line -> line.contains("AGENT_TOOL_FAILED"));
        assertThat(worker.invocationsOf("triage")).isEqualTo(1);
    }

    @Test
    void aModelCallThatFailsFinallyFailsTheAgent() {
        worker.on("triage", new ScriptedModel().thenFail("[non-retryable] LLM_401: bad key"));

        createProcess("agent-support", "ag-modelfail", new Variable("customerId", "C"), new Variable("message", "m"));

        assertThat(process("ag-modelfail").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(errorsOf("ag-modelfail")).anyMatch(line -> line.contains("AGENT_MODEL_FAILED"));
    }

    @Test
    void aFailureAfterTheAgentCompensatesEveryCompletedToolCallNewestFirst() {
        worker.on("triage", new ScriptedModel()
                .thenCall(call("openRefund", Map.of("orderId", "A", "amount", 1)))
                .thenCall(call("openRefund", Map.of("orderId", "B", "amount", 2)))
                .thenAnswer("{\"decision\":\"refunded\"}"));
        worker.on("open-refund", (req, cb, n) -> cb.complete(List.of(TestWorker.var("refundId", "RF-" + var(req, "orderId")))));
        var cancelled = new CopyOnWriteArrayList<String>();
        worker.on("cancel-refund", (req, cb, n) -> {
            cancelled.add(var(req, "refundId") + "/" + var(req, "orderId"));
            cb.complete();
        });
        worker.on("confirm", TestWorker.failWith("[non-retryable] cannot confirm"));

        createProcess("agent-support", "ag-saga", new Variable("customerId", "C"), new Variable("message", "m"));

        assertThat(process("ag-saga").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
        assertThat(cancelled).containsExactly("RF-B/B", "RF-A/A");
    }

    @Test
    void cancellingTheProcessMidLoopCancelsTheAgentAndItsToolCalls() {
        var model = new ScriptedModel().thenCall(call("lookupOrder", Map.of("orderId", "1")));
        worker.on("triage", model);
        worker.on("lookup-order", TestWorker.deferForever());

        createProcess("agent-support", "ag-cancel", new Variable("customerId", "C"), new Variable("message", "m"));
        cancelProcessUseCase.handle(new CancelProcessCommand(process("ag-cancel").getId()));

        assertThat(process("ag-cancel").getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(agent("ag-cancel", "triage").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(children("ag-cancel", "triage")).filteredOn(c -> "lookup-order".equals(c.getStepId()))
                .extracting(StepExecution::getStatus).containsExactly(StepExecutionStatus.CANCELLED);
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void anApprovalDecidesWhetherAToolRunsAndToolErrorsCanBeReportedToTheModel() {
        var model = new ScriptedModel()
                .thenCall(call("openRefund", Map.of("orderId", "X", "amount", 999)))
                .thenCall(call("openRefund", Map.of("orderId", "X", "amount", 9)))
                .thenCall(call("flaky", Map.of()))
                .thenAnswer("handled");
        worker.on("agent", model);
        var approvals = new CopyOnWriteArrayList<TaskExecutionRequested>();
        worker.on("agent.openRefund.approval", (req, cb, n) -> {
            approvals.add(req);
            cb.complete(n == 1 ? List.of(TestWorker.var("approved", "false"), TestWorker.var("comment", "too much"))
                    : List.of(TestWorker.var("approved", "true")));
        });
        worker.on("open-refund", (req, cb, n) -> cb.complete(List.of(TestWorker.var("refundId", "RF-" + var(req, "amount")))));
        worker.on("flaky", TestWorker.failWith("[non-retryable] down"));

        createProcess("agent-guarded", "ag-appr", new Variable("ticket", "T-1"));

        assertThat(process("ag-appr").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(processVariable("ag-appr", "answer")).isEqualTo("handled");
        assertThat(approvals).hasSize(2);
        assertThat(approvals.getFirst().taskId()).isEqualTo("complete-form");
        assertThat(var(approvals.getFirst(), "formId")).isEqualTo("approve-refund");
        assertThat(var(approvals.getFirst(), "amount")).isEqualTo("999");
        assertThat(worker.invocationsOf("open-refund")).isEqualTo(1);
        var second = model.requests().get(1).toString();
        assertThat(second).contains("rejected this call").contains("too much");
        assertThat(model.requests().get(2).toString()).contains("RF-9");
        assertThat(model.lastToolResults().getFirst().path("content").asText()).contains("ended ERROR");
    }

    @Test
    void spendingMoreTokensThanMaxTokensFailsTheAgent() {
        worker.on("agent", new ScriptedModel().costing(600)
                .thenCall(call("flaky", Map.of()))
                .thenCall(call("flaky", Map.of())));
        worker.on("flaky", TestWorker.succeed());

        createProcess("agent-guarded", "ag-tokens", new Variable("ticket", "T-2"));

        assertThat(process("ag-tokens").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(errorsOf("ag-tokens")).anyMatch(line -> line.contains("AGENT_MAX_TOKENS"));
        assertThat(worker.invocationsOf("flaky")).isEqualTo(1);
    }

    @Test
    void theTranscriptRedactsSecretsAndTheRequestKeepsTheFullResult() throws Exception {
        var model = new ScriptedModel().thenCall(call("lookupOrder", Map.of("orderId", "1"))).thenAnswer("{\"decision\":\"ok\"}");
        worker.on("triage", model);
        worker.on("lookup-order", (req, cb, n) -> cb.complete(List.of(TestWorker.var("apiToken", "s3cr3t"),
                TestWorker.var("status", "OK"))));

        createProcess("agent-support", "ag-redact", new Variable("customerId", "C"), new Variable("message", "m"));

        var transcript = JSON.readTree(agent("ag-redact", "triage").getOutputJson());
        assertThat(transcript.toString()).doesNotContain("s3cr3t").contains("***");
        assertThat(transcript.path("turns").asInt()).isEqualTo(2);
        assertThat(model.lastToolResults().getFirst().path("content").asText()).contains("s3cr3t");
    }
}
