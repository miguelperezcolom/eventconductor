package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** AGENT_CALL in the test kit: a scripted model, its tools answered by the ordinary stubs. */
@EventConductorTest
class AgentStubTest {

    @Test
    void aScriptedAgentLooksUpTheOrderGetsItsRefundApprovedAndAnswers(WorkflowTestRuntime runtime) {
        var model = runtime.whenAgent("triage")
                .thenCallTool("lookupOrder", Map.of("orderId", "O-1"))
                .thenCallTools(AgentStub.call("openRefund", Map.of("orderId", "O-1", "amount", 20)),
                        AgentStub.call("notifyCustomer", Map.of("text", "Your refund is on its way")))
                .thenAnswer("{\"decision\":\"refund\"}");
        var orders = runtime.whenHttpCall("triage.lookupOrder").thenRespond(200, "{\"status\":\"DELIVERED\",\"total\":20}");
        runtime.whenStep("issue-refund").thenComplete(Map.of("refundId", "RF-1"));
        runtime.whenTask("emails").thenComplete(Map.of("sent", true));

        var process = runtime.start("support-triage", "t-1", Map.of("customerId", "C-7", "message", "broken mug"));
        runtime.completeUserTask(process, "triage.openRefund.approval", Map.of("approved", true));

        assertThat(process).isCompleted().hasVariable("decision", "refund").doesNotHaveVariable("refundId");
        assertThat(orders.lastRequest()).containsEntry("url", "https://orders.example.com/orders/O-1");
        assertThat(model.requests()).hasSize(3);
        assertThat(model.lastToolResults()).hasSize(2);
        assertThat(model.lastToolResults().get(0).get("content")).isEqualTo(Map.of("refundId", "RF-1"));
        assertThat(model.lastToolResults().get(1).get("content")).isEqualTo(Map.of("sent", "true"));
        assertThat(runtime.invocationsOf("notify-customer")).hasSize(1);
    }

    @Test
    void aRejectedApprovalIsToldToTheModelAndAnUnscriptedAgentFails(WorkflowTestRuntime runtime) {
        var model = runtime.whenAgent("triage")
                .thenCallTool("openRefund", Map.of("orderId", "O-2", "amount", 500))
                .thenAnswer("{\"decision\":\"escalate\"}");

        var process = runtime.start("support-triage", "t-2", Map.of("customerId", "C", "message", "m"));
        runtime.completeUserTask(process, "triage.openRefund.approval", Map.of("approved", false, "comment", "too much"));

        assertThat(process).isCompleted().hasVariable("decision", "escalate");
        assertThat(String.valueOf(model.lastToolResults().getFirst().get("content"))).contains("too much");
        assertThat(runtime.invocationsOf("issue-refund")).isEmpty();
    }

    @Test
    void anAgentWithoutAScriptFailsWithoutCallingAModel(WorkflowTestRuntime runtime) {
        assertThat(runtime.start("support-triage", "t-3", Map.of("customerId", "C", "message", "m"))).isFailed();
    }

    @Test
    void aTurnCanFailAndTokensCanRunOut(WorkflowTestRuntime runtime) {
        runtime.whenAgent("triage").thenFail("LLM_401", false);
        assertThat(runtime.start("support-triage", "t-4", Map.of("customerId", "C", "message", "m"))).isFailed();

        runtime.whenAgent("triage").costing(30_000, 30_000).thenCallTool("notifyCustomer", Map.of());
        runtime.whenTask("emails").thenComplete(Map.of());
        assertThat(runtime.start("support-triage", "t-5", Map.of("customerId", "C", "message", "m"))).isFailed();
    }
}
