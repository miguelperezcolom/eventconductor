package io.mateu.workflow.e2e;

import io.mateu.e2ejpa.crashrecovery.CrashRecoveryTestApp;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.e2e.support.ScriptedModel;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.e2e.support.ScriptedModel.call;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An agent survives its node: node A runs the first model turn and dispatches the tool call it asked for,
 * then dies with the tool still at its worker. Node B, on the same database, takes the tool's late reply
 * and carries the loop on — the next turn's conversation rebuilt from the rows alone — to the answer.
 */
class AgentCallCrashResumeE2eTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String DB_URL =
            "jdbc:h2:file:./target/agent-crash-db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL";

    private ConfigurableApplicationContext boot(String ddlAuto) {
        return new SpringApplicationBuilder(CrashRecoveryTestApp.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--workflow.mode=embedded",
                        "--workflow.persistence=jpa",
                        "--workflow.cron-enabled=false",
                        "--workflow.outbox.relay-enabled=true",
                        "--workflow.outbox-poll-interval-ms=50",
                        "--workflow.timeout-scan-interval-ms=200",
                        "--spring.datasource.url=" + DB_URL,
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.jpa.hibernate.ddl-auto=" + ddlAuto,
                        "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false");
    }

    private static StepExecution toolOf(ConfigurableApplicationContext node, Process process) {
        return node.getBean(StepExecutionRepository.class).findByProcess(process).stream()
                .filter(s -> "lookup-order".equals(s.getStepId()) && s.isIteration())
                .findFirst().orElse(null);
    }

    @Test
    void theLoopResumesOnAnotherNodeFromThePersistedTurnsAndToolCalls() {
        var businessKey = "agent-crash-1";
        String toolExecutionId;
        int attempt;
        try (var nodeA = boot("create")) {
            nodeA.getBean(TestWorker.class).on("triage", new ScriptedModel().thenCall(call("lookupOrder", Map.of("orderId", "O-9"))));
            nodeA.getBean(TestWorker.class).on("lookup-order", TestWorker.deferForever());
            nodeA.getBean(ProcessUpstreamEventUseCase.class).handle(new ProcessUpstreamEventCommand(new ProcessCreationRequested(
                    "agent-support", businessKey, List.of(new io.mateu.workflow.dtos.Variable("customerId", "C"),
                    new io.mateu.workflow.dtos.Variable("message", "m")))));
            var processes = nodeA.getBean(ProcessRepository.class);
            await().atMost(TIMEOUT).until(() -> processes.findByBusinessKey(businessKey)
                    .map(p -> toolOf(nodeA, p)).map(t -> t.getStatus() == StepExecutionStatus.PENDING).orElse(false));
            var tool = toolOf(nodeA, processes.findByBusinessKey(businessKey).orElseThrow());
            toolExecutionId = tool.getId();
            attempt = tool.getDispatchCount();
        }

        try (var nodeB = boot("update")) {
            var model = new ScriptedModel().thenAnswer("{\"decision\":\"found it\"}");
            nodeB.getBean(TestWorker.class).on("triage", model);
            // The tool's worker answers after the crash; node B takes the reply.
            nodeB.getBean(UpdateStepExecutionUseCase.class).handle(new UpdateStepExecutionCommand(toolExecutionId,
                    List.of(new Variable("status", "SHIPPED")), "", StepExecutionStatus.COMPLETED, false, attempt));
            var processes = nodeB.getBean(ProcessRepository.class);
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(processes.findByBusinessKey(businessKey)
                    .map(Process::getStatus)).contains(ProcessStatus.COMPLETED));
            assertThat(model.requests()).hasSize(1);
            assertThat(model.lastToolResults().getFirst().path("content").asText()).isEqualTo("{\"status\":\"SHIPPED\"}");
            assertThat(model.requests().getFirst().path("messages").get(1).path("toolCalls").get(0).path("name").asText())
                    .isEqualTo("lookupOrder");
        }
    }
}
