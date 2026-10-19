package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.MigrationScenario;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.application.readmodel.ProcessIndexQueryService;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Migration in embedded + JPA mode: the target read from the definition's version history (so a process
 * can be moved to any recorded version, back to an older one too), carried out through the outbox relay
 * under the database process lock, the re-armed subscription persisted and found by its index, and the
 * process index told the new version.
 */
@TestPropertySource(properties = "workflow.projection.enabled=true")
class MigrationJpaE2eTest extends AbstractJpaE2eTest {

    static final AuthorizationContext BOB = new AuthorizationContext("bob", List.of(), List.of());

    @Autowired ProcessOperationsService operations;
    @Autowired WorkflowDefinitionRepository definitions;
    @Autowired LogMessageRepository logMessageRepository;
    @Autowired ProcessIndexQueryService processIndex;

    final Map<String, TestWorker.WorkerCallback> held = new ConcurrentHashMap<>();

    private StepExecution current(String businessKey, String stepId) {
        return steps(businessKey).stream()
                .filter(se -> stepId.equals(se.getStepId()) && !se.isSuperseded() && !se.isIteration())
                .findFirst().orElseThrow(() -> new AssertionError("no current execution of " + stepId));
    }

    @Test
    void aProcessMovesToARecordedVersionAndBackAndTheIndexFollows() {
        worker.on("b", (request, callback, n) -> held.put("b", callback));
        createProcess("mig-linear", "jpa-mig-1");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(held).containsKey("b"));
        var v1 = process("jpa-mig-1").getWorkflowDefinitionVersion();

        var v2 = new MigrationScenario(definitions).deploy("mig-linear.v2-added.json").version();
        assertThat(v2).isGreaterThan(v1);
        assertThat(operations.migrationVersions("mig-linear")).contains(v1, v2);

        operations.migrate(process("jpa-mig-1").getId(), v2, Map.of(), Map.of(), "to v2", BOB);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(process("jpa-mig-1").getWorkflowDefinitionVersion()).isEqualTo(v2);
            assertThat(processIndex.findByBusinessKey("jpa-mig-1")).hasValueSatisfying(row ->
                    assertThat(row.workflowDefinitionVersion()).isEqualTo(v2));
        });
        assertThat(current("jpa-mig-1", "x").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        // And back to v1, read from the history: d (not started) is dropped, c comes back as a new step.
        operations.migrate(process("jpa-mig-1").getId(), v1, Map.of(), Map.of(), "v2 was wrong after all", BOB);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(process("jpa-mig-1").getWorkflowDefinitionVersion()).isEqualTo(v1));

        held.get("b").complete();
        awaitStatus("jpa-mig-1", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("c")).isEqualTo(1);
        assertThat(worker.invocationsOf("d")).isZero();
        assertThat(worker.invocationsOf("b")).isEqualTo(1);
        var audit = logMessageRepository.findByProcessId(process("jpa-mig-1").getId()).stream()
                .filter(OperatorAudit::isOperatorLine).map(line -> line.getMessage()).toList();
        assertThat(audit).anySatisfy(line -> assertThat(line).contains("migrated from v" + v1 + " to v" + v2))
                .anySatisfy(line -> assertThat(line).contains("migrated from v" + v2 + " to v" + v1));
        assertThat(outboxRepository.findAll()).anySatisfy(row -> assertThat(row.getPayload()).contains("process-migrated"));

        assertThatThrownBy(() -> operations.migrate(process("jpa-mig-1").getId(), 99, Map.of(), Map.of(), "x", BOB))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has no version 99");
    }

    @Test
    void aReArmedMessageWaitIsFoundByItsNewSubscription() {
        createProcess("mig-wait", "jpa-mig-2", new Variable("orderId", "O-1"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(current("jpa-mig-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        var v2 = new MigrationScenario(definitions).deploy("mig-wait.v2.json").version();

        var plan = operations.dryRunMigration(process("jpa-mig-2").getId(), v2, Map.of(), Map.of("invoiceId", "INV-1"), BOB);
        assertThat(plan.plan().executable()).isTrue();
        operations.migrate(process("jpa-mig-2").getId(), v2, Map.of(), Map.of("invoiceId", "INV-1"), "settlement", BOB);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stepExecutionRepository.findWaitingForMessage("payment-settled", "INV-1")).hasSize(1));
        assertThat(stepExecutionRepository.findWaitingForMessage("payment-received", "O-1")).isEmpty();
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
                new MessageReceived("payment-settled", "INV-1", List.of())));
        awaitStatus("jpa-mig-2", ProcessStatus.COMPLETED);
    }

    /**
     * A target that declares sensitive variables the source did not: the migration encrypts what is already
     * stored (process and step rows, history included) and the values its mapping sets; the dry run and the
     * audit line name them, never quote them.
     */
    @Test
    void migratingToAVersionThatDeclaresSensitiveVariablesEncryptsTheStoredValues() {
        var card = "4111111111111111";
        worker.on("b", (request, callback, n) -> held.put("b", callback));
        createProcess("mig-linear", "jpa-mig-sensitive", new Variable("cardNumber", card), new Variable("amount", "120"));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(held).containsKey("b"));
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var id = process("jpa-mig-sensitive").getId();
        assertThat(jdbc.queryForObject("select variables from process_entity where id = ?", String.class, id))
                .as("v1 declares nothing sensitive").contains(card);

        var v2 = new MigrationScenario(definitions).deploy("mig-linear.v2-sensitive.json").version();
        var plan = operations.dryRunMigration(id, v2, Map.of(), Map.of("pin", "9876"), BOB);
        assertThat(plan.plan().executable()).isTrue();
        assertThat(plan.plan().warnings()).extracting(io.mateu.workflow.analysis.MigrationRules.Problem::message)
                .anySatisfy(m -> assertThat(m).contains("[cardNumber, pin] become sensitive"))
                .allSatisfy(m -> assertThat(m).doesNotContain(card).doesNotContain("9876"));

        operations.migrate(id, v2, Map.of(), Map.of("pin", "9876"), "card data is PCI scope now", BOB);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(process("jpa-mig-sensitive").getWorkflowDefinitionVersion()).isEqualTo(v2));

        var row = jdbc.queryForObject("select variables from process_entity where id = ?", String.class, id);
        assertThat(row).contains("ec:enc:v1:e2e-test:").doesNotContain(card).doesNotContain("9876").contains("120");
        assertThat(jdbc.queryForList("select variables from step_execution_entity where process_id = ?", String.class, id))
                .isNotEmpty().allSatisfy(stepRow -> assertThat(String.valueOf(stepRow)).doesNotContain(card));
        assertThat(process("jpa-mig-sensitive").getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("cardNumber", card),
                        new io.mateu.workflow.domain.aggregates.Variable("pin", "9876"));
        assertThat(logMessageRepository.findByProcessId(id).stream().map(line -> line.getMessage()).toList())
                .anySatisfy(line -> assertThat(line).contains("migrated from v").contains("pin = '••••'"))
                .allSatisfy(line -> assertThat(line).doesNotContain(card).doesNotContain("9876"));

        held.get("b").complete();
        awaitStatus("jpa-mig-sensitive", ProcessStatus.COMPLETED);
    }

    @Autowired javax.sql.DataSource dataSource;
}
