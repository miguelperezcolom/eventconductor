package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessStateConflictException;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The manual operations in embedded + JPA mode: requested at the door, carried out through the outbox
 * relay under the database process lock, every effect persisted — the audit lines included — and the
 * failure read back as an incident from the database.
 */
class InstanceOperationsJpaE2eTest extends AbstractJpaE2eTest {

    static final AuthorizationContext BOB = new AuthorizationContext("bob", List.of(), List.of());

    @Autowired ProcessOperationsService operations;
    @Autowired IncidentService incidents;
    @Autowired LogMessageRepository logMessageRepository;

    @Test
    void aFailedStepIsAnIncidentAndCompletingItByHandFinishesTheProcess() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.failWith("[non-retryable] Payment 4711 declined"));
        worker.on("c", TestWorker.succeed());
        createProcess("ops-linear", "jpa-ops-1");
        awaitStatus("jpa-ops-1", ProcessStatus.ERROR);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incidents.incidents("ops-linear").groups())
                .anySatisfy(group -> {
                    assertThat(group.stepId()).isEqualTo("b");
                    assertThat(group.errorFamily()).isEqualTo("Payment # declined");
                    assertThat(group.processes()).isEqualTo(1);
                }));

        operations.completeStep(process("jpa-ops-1").getId(), step("jpa-ops-1", "b").getId(),
                List.of(new Variable("paymentRef", "PHONE-9")), "Paid by phone", BOB);

        awaitStatus("jpa-ops-1", ProcessStatus.COMPLETED);
        assertThat(process("jpa-ops-1").getFinished()).isNotNull();
        assertThat(process("jpa-ops-1").getVariables()).contains(new Variable("paymentRef", "PHONE-9"));
        assertThat(audit("jpa-ops-1")).anySatisfy(line -> assertThat(line).contains("[complete-step] by bob"));
        assertThat(incidents.incidents("ops-linear").groups()).noneMatch(group -> "b".equals(group.stepId())
                && group.errorFamily().equals("Payment # declined"));
    }

    @Test
    void retryingFromAStepReopensTheFailedProcess() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());
        worker.on("c", TestWorker.failWith("[non-retryable] boom"));
        createProcess("ops-linear", "jpa-ops-2");
        awaitStatus("jpa-ops-2", ProcessStatus.ERROR);

        worker.on("c", TestWorker.succeed());
        operations.retryFromStep(process("jpa-ops-2").getId(), "b", "Fixed downstream", BOB);

        awaitStatus("jpa-ops-2", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("a")).isEqualTo(1);
        assertThat(worker.invocationsOf("b")).isEqualTo(2);
        assertThat(step("jpa-ops-2", "c").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(audit("jpa-ops-2")).anySatisfy(line -> assertThat(line).contains("[retry-from-step] by bob"));
    }

    @Test
    void settingAVariableReleasesAGuardedStep() {
        worker.on("prepare", TestWorker.succeed());
        worker.on("ship", TestWorker.succeed());
        createProcess("ops-guard", "jpa-ops-3", new io.mateu.workflow.dtos.Variable("approved", "false"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("jpa-ops-3", "prepare").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED));

        operations.setVariables(process("jpa-ops-3").getId(), List.of(new Variable("approved", "true")), List.of(),
                "Approved", BOB);

        awaitStatus("jpa-ops-3", ProcessStatus.COMPLETED);
        assertThat(audit("jpa-ops-3")).anySatisfy(line -> assertThat(line)
                .contains("[set-variables] by bob").contains("set approved = 'true' (was 'false')"));
    }

    @Test
    void aCompletedProcessIsRefusedAtTheDoor() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());
        worker.on("c", TestWorker.succeed());
        createProcess("ops-linear", "jpa-ops-4");
        awaitStatus("jpa-ops-4", ProcessStatus.COMPLETED);

        assertThatThrownBy(() -> operations.retryFromStep(process("jpa-ops-4").getId(), "a", "why", BOB))
                .isInstanceOf(ProcessStateConflictException.class);
    }

    @Test
    void anIterationCompletedByHandIsCollectedByItsForEach() {
        worker.on("line", (request, callback, n) -> {
            if (n > 1) {
                callback.complete(List.of(TestWorker.var("ok", "worker")));
            }
        });
        createProcess("foreach-sequential", "jpa-ops-5",
                new io.mateu.workflow.dtos.Variable("order", "{\"lines\":[{\"sku\":\"A\"},{\"sku\":\"B\"}]}"));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(steps("jpa-ops-5")).anyMatch(se -> se.isIteration()
                && se.getStatus() == StepExecutionStatus.PENDING));
        var first = steps("jpa-ops-5").stream().filter(se -> se.isIteration()
                && se.getStatus() == StepExecutionStatus.PENDING).findFirst().orElseThrow();

        operations.completeStep(process("jpa-ops-5").getId(), first.getId(), List.of(new Variable("ok", "manual")),
                "Handled by hand", BOB);

        awaitStatus("jpa-ops-5", ProcessStatus.COMPLETED);
        assertThat(process("jpa-ops-5").getVariables())
                .contains(new Variable("handled", "[{\"ok\":\"manual\"},{\"ok\":\"worker\"}]"));
    }

    private List<String> audit(String businessKey) {
        return logMessageRepository.findByProcessId(process(businessKey).getId()).stream()
                .filter(OperatorAudit::isOperatorLine)
                .map(io.mateu.workflow.domain.aggregates.LogMessage::getMessage)
                .toList();
    }
}
