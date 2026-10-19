package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.application.services.SearchAttributeIndexer;
import io.mateu.workflow.application.services.SensitiveFixtures;
import io.mateu.workflow.application.services.VariableProtection;
import io.mateu.workflow.crypto.VariableEnvelope;
import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.out.async.OutboxSignal;
import io.mateu.workflow.infra.out.memory.InMemoryProcessTraceContextRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static io.mateu.workflow.application.services.SensitiveFixtures.CARD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sensitive variables at rest, on a migrated database (V53) — H2 on every build and PostgreSQL wherever
 * Docker is: what the columns hold is ciphertext (read raw, with SQL), what the repositories give back is
 * the plaintext, and the rows of everything that is not sensitive are unchanged.
 */
abstract class SensitiveVariablesStorageContract {

    DataSource dataSource;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    ProcessDBRepository processes;
    StepExecutionDBRepository steps;
    io.mateu.workflow.crypto.VariableEncryption keys;

    abstract DataSource newMigratedDatabase();

    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void open() {
        dataSource = newMigratedDatabase();
        context = SensitiveJpaTestContext.open(dataSource);
        jdbc = context.getBean(JdbcTemplate.class);
        keys = SensitiveFixtures.keys();
        var protection = new VariableProtection(SensitiveFixtures.policies(keys, null, null));
        var none = new StaticListableBeanFactory();
        processes = new ProcessDBRepository(context.getBean(ProcessEntityRepository.class),
                context.getBean(OutboxMessageEntityRepository.class), WorkflowTracing.NOOP, new OutboxSignal(),
                new InMemoryProcessTraceContextRepository(), new ProcessStatusAnnouncer(false, ""),
                none.getBeanProvider(io.mateu.workflow.application.out.RollupAnalyticsPort.class),
                none.getBeanProvider(io.mateu.workflow.application.sync.EngineReplyPolicy.class),
                none.getBeanProvider(io.mateu.workflow.application.out.ReplySignal.class),
                none.getBeanProvider(ReplyNotifier.class),
                new SearchAttributeIndexer(none.getBeanProvider(io.mateu.workflow.application.out.ProcessSearchAttributeRepository.class)),
                SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class)),
                protection);
        steps = new StepExecutionDBRepository(context.getBean(StepExecutionEntityRepository.class),
                context.getBean(OutboxMessageEntityRepository.class), WorkflowTracing.NOOP, new OutboxSignal(),
                none.getBeanProvider(io.mateu.workflow.application.out.RollupAnalyticsPort.class), protection);
    }

    @AfterEach
    void close() {
        context.close();
        dispose(dataSource);
    }

    @Test
    void aProcessRowHoldsCiphertextAndTheRepositoryGivesBackPlaintext() {
        var process = io.mateu.workflow.domain.aggregates.Process.create("p1", SensitiveFixtures.definition(), "bk-1",
                List.of(new Variable("cardNumber", CARD), new Variable("amount", "120"),
                        new Variable("patient", "{\"ssn\":\"123-45-6789\"}")));
        process.recordReply(new ProcessReply("charge", "{\"cardNumber\":\"" + CARD + "\"}",
                ProcessReply.Outcome.REPLIED, ProcessReply.Compensation.NONE, null, LocalDateTime.of(2026, 10, 19, 9, 0)));
        processes.save(process);

        var raw = jdbc.queryForMap("select variables, reply_json from process_entity where id = 'p1'");
        var variables = String.valueOf(raw.get("variables"));
        assertThat(variables).doesNotContain(CARD).doesNotContain("123-45-6789")
                .contains("\"cardNumber\"").contains(VariableEnvelope.PREFIX + "test-key:")
                .contains("\"amount\"").contains("\"120\"");
        assertThat(String.valueOf(raw.get("reply_json"))).startsWith(VariableEnvelope.PREFIX).doesNotContain(CARD);
        // The ProcessCreated event in the outbox carries the values sealed too.
        var payloads = jdbc.queryForList("select payload from outbox_message_entity", String.class);
        assertThat(payloads).isNotEmpty().allSatisfy(payload -> assertThat(payload).doesNotContain(CARD));
        assertThat(payloads).anySatisfy(payload -> assertThat(payload).contains(VariableEnvelope.PREFIX));

        var read = processes.findById("p1").orElseThrow();
        assertThat(read.getVariables()).containsExactly(new Variable("cardNumber", CARD), new Variable("amount", "120"),
                new Variable("patient", "{\"ssn\":\"123-45-6789\"}"));
        assertThat(read.getReply().payload()).contains(CARD);
    }

    @Test
    void aStepRowHoldsCiphertextForItsSensitiveVariablesAndItsForEachBlobs() {
        var process = io.mateu.workflow.domain.aggregates.Process.create("p2", SensitiveFixtures.definition(), "bk-2",
                List.of(new Variable("cardNumber", CARD)));
        processes.save(process);
        var step = SensitiveFixtures.definition().steps().get(1);
        var execution = StepExecution.create(step, "p2", 1).toBuilder()
                .variables(List.of(new Variable("cardNumber", CARD), new Variable("authCode", "A-77"),
                        new Variable("amount", "120")))
                .scopeJson("{\"item\":\"" + CARD + "\"}")
                .outputJson("[{\"authCode\":\"A-77\"}]")
                .build();
        steps.save(execution);

        var raw = jdbc.queryForMap("select variables, scope_json, output_json from step_execution_entity where id = ?",
                execution.id());
        assertThat(String.valueOf(raw.get("variables"))).doesNotContain(CARD).doesNotContain("A-77").contains("\"120\"");
        assertThat(String.valueOf(raw.get("scope_json"))).startsWith(VariableEnvelope.PREFIX);
        assertThat(String.valueOf(raw.get("output_json"))).startsWith(VariableEnvelope.PREFIX);

        var read = steps.findById(execution.id()).orElseThrow();
        assertThat(read.getVariables()).contains(new Variable("cardNumber", CARD), new Variable("authCode", "A-77"));
        assertThat(read.getScopeJson()).contains(CARD);
        assertThat(read.getOutputJson()).contains("A-77");
    }

    @Test
    void aProcessThatDeclaresNothingIsStoredAsBefore() {
        var plain = SensitiveFixtures.definition().withSensitiveVariables(null).withSteps(List.of());
        processes.save(io.mateu.workflow.domain.aggregates.Process.create("p3", plain, "bk-3",
                List.of(new Variable("cardNumber", CARD))));
        assertThat(jdbc.queryForObject("select variables from process_entity where id = 'p3'", String.class))
                .contains(CARD).doesNotContain(VariableEnvelope.PREFIX);
    }

    @Test
    void theDeclarationIsStoredWithTheDefinition() {
        // V53's column, read back through the entity mapping.
        jdbc.update("insert into workflow_definition_entity (id, name, version, sensitive_variables_json) values"
                + " ('payment', 'Payment', 1, '[\"cardNumber\"]')");
        assertThat(context.getBean(WorkflowDefinitionEntityRepository.class).findById("payment").orElseThrow()
                .getSensitiveVariablesJson()).isEqualTo("[\"cardNumber\"]");
    }
}
