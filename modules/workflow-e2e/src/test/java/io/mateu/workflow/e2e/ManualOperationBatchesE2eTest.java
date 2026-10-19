package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationParameters;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The manual operations as batch types: SET_VARIABLES and RETRY_FROM_STEP over every process a filter
 * matches, each through its own use case, and audited with the batch's creator as the operator.
 */
class ManualOperationBatchesE2eTest extends AbstractE2eTest {

    static final AuthorizationContext BOB = new AuthorizationContext("bob", List.of(), List.of());

    @Autowired BatchOperationService batches;

    private List<LogMessage> auditOf(String businessKey) {
        return logMessageRepository.findByProcessId(process(businessKey).getId()).stream()
                .filter(OperatorAudit::isOperatorLine).toList();
    }

    private void awaitCompleted(String batchId) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(batches.get(batchId).orElseThrow().status()).isEqualTo(BatchOperationStatus.COMPLETED));
    }

    @Test
    void setVariablesAndRetryFromStepRunOverAFilterAndAreAudited() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.deferForever());
        createProcess("ops-linear", "mob-1");
        createProcess("ops-linear", "mob-2");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(process("mob-1").getStatus()).isEqualTo(ProcessStatus.RUNNING);
            // b is with its (silent) worker: in flight.
            assertThat(step("mob-2", "b").getStatus().name()).isIn("PENDING", "RUNNING");
            assertThat(step("mob-1", "b").getStatus().name()).isIn("PENDING", "RUNNING");
        });
        var filter = new BatchOperationFilter("ops-linear", null, Set.of(ProcessStatus.RUNNING), null, null, "mob-",
                null, false, null);

        var setVariables = batches.create(BatchOperationType.SET_VARIABLES, filter,
                BatchOperationParameters.of(Map.of("approved", "yes"), List.of(), null, "data fix"), BOB);
        awaitCompleted(setVariables.id());
        assertThat(batches.get(setVariables.id()).orElseThrow().succeeded()).isEqualTo(2);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (var key : List.of("mob-1", "mob-2")) {
                assertThat(process(key).getVariables()).anyMatch(v -> v.name().equals("approved") && "yes".equals(v.value()));
                assertThat(auditOf(key)).anySatisfy(line -> assertThat(line.getMessage())
                        .contains("[set-variables] by bob").contains("data fix (batch " + setVariables.id() + ")"));
            }
        });

        var aBefore = worker.invocationsOf("a");
        var retryFrom = batches.create(BatchOperationType.RETRY_FROM_STEP, filter,
                BatchOperationParameters.of(null, null, "a", "replay"), BOB);
        awaitCompleted(retryFrom.id());
        assertThat(batches.get(retryFrom.id()).orElseThrow().succeeded()).isEqualTo(2);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(worker.invocationsOf("a")).isGreaterThanOrEqualTo(aBefore + 2);
            for (var key : List.of("mob-1", "mob-2")) {
                assertThat(auditOf(key)).anySatisfy(line -> assertThat(line.getMessage())
                        .contains("[retry-from-step] by bob").contains("replay (batch " + retryFrom.id() + ")"));
            }
        });

        // A step the definition does not have: not applicable, nothing failed.
        var unknownStep = batches.create(BatchOperationType.RETRY_FROM_STEP, filter,
                BatchOperationParameters.of(null, null, "nope", "replay"), BOB);
        awaitCompleted(unknownStep.id());
        assertThat(batches.get(unknownStep.id()).orElseThrow().skippedByReason()).containsEntry("notApplicable", 2L);

        assertThatThrownBy(() -> batches.create(BatchOperationType.SET_VARIABLES, filter,
                BatchOperationParameters.of(Map.of("x", "1"), null, null, null), BOB)).hasMessageContaining("reason");
        assertThatThrownBy(() -> batches.create(BatchOperationType.RETRY_FROM_STEP, filter,
                BatchOperationParameters.of(null, null, null, "r"), BOB)).hasMessageContaining("stepId");
        assertThatThrownBy(() -> batches.create(BatchOperationType.SET_VARIABLES, filter,
                BatchOperationParameters.of(null, null, null, "r"), BOB)).hasMessageContaining("Nothing to change");

        for (var key : List.of("mob-1", "mob-2")) {
            cancelProcessUseCase.handle(new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(
                    process(key).getId()));
        }
    }
}
