package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.domain.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapter between a {@link FormExecution} and its row.
 *
 * <p>Worth a test of its own rather than leaving it to whatever exercises it from above, because
 * everything it does is a translation that can lose something quietly: a status enum through a
 * string column, and two lists of name/value pairs through TEXT columns of JSON. A form execution
 * that comes back without the answers somebody typed is not an error anywhere — it is an empty
 * form, and the process moves on.
 */
@DataJpaTest
class FormExecutionDBRepositoryTest {

    @Autowired
    FormExecutionEntityRepository entities;

    private FormExecutionDBRepository repository;

    @BeforeEach
    void setUp() {
        entities.deleteAll();
        repository = new FormExecutionDBRepository(entities);
    }

    @Test
    void an_execution_comes_back_with_its_status_variables_and_values_intact() {
        repository.save(execution("fe-1", FormExecutionStatus.COMPLETED,
                List.of(new Variable("tenant", "acme")),
                List.of(new Value("decision", "WALK"), new Value("comment", "no rooms"))));

        var stored = repository.findById("fe-1").orElseThrow();

        assertThat(stored.formId()).isEqualTo("checkin");
        assertThat(stored.processId()).isEqualTo("process-1");
        assertThat(stored.stepId()).isEqualTo("step-1");
        assertThat(stored.stepExecutionId()).isEqualTo("exec-1");
        assertThat(stored.status()).isEqualTo(FormExecutionStatus.COMPLETED);
        assertThat(stored.userId()).isEqualTo("alice");
        assertThat(stored.userGroup()).isEqualTo("reception");
        assertThat(stored.variables()).containsExactly(new Variable("tenant", "acme"));
        // The answers, in the order they were given: a form's values are read back positionally by
        // whoever renders them, so a round trip that reorders them is a round trip that lies.
        assertThat(stored.values()).containsExactly(
                new Value("decision", "WALK"), new Value("comment", "no rooms"));
    }

    @Test
    void with_an_encryption_key_every_variable_is_stored_sealed_and_read_back_in_clear() {
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var keys = new io.mateu.workflow.crypto.VariableEncryption(
                new io.mateu.workflow.crypto.AesGcmVariableCodec("forms", java.util.Map.of("forms", key)));
        repository.encryption(keys);
        var alreadySealed = keys.seal("123-45-6789");
        repository.save(execution("fe-9", FormExecutionStatus.PENDING,
                List.of(new Variable("cardNumber", "4111111111111111"), new Variable("ssn", alreadySealed)), List.of()));

        var row = entities.findById("fe-9").orElseThrow().getVariables();
        assertThat(row).doesNotContain("4111111111111111").contains("ec:enc:v1:forms:").contains(alreadySealed);
        assertThat(repository.findById("fe-9").orElseThrow().variables()).containsExactly(
                new Variable("cardNumber", "4111111111111111"), new Variable("ssn", "123-45-6789"));
        // Without the key, the sealed values are handed on as they are rather than failing the inbox.
        repository.encryption(null);
        assertThat(repository.findById("fe-9").orElseThrow().variables().getFirst().value()).startsWith("ec:enc:v1:");
    }

    @Test
    void an_execution_with_nothing_filled_in_yet_round_trips_as_empty_rather_than_null() {
        repository.save(execution("fe-2", FormExecutionStatus.PENDING, List.of(), List.of()));

        var stored = repository.findById("fe-2").orElseThrow();

        assertThat(stored.variables()).isEmpty();
        assertThat(stored.values()).isEmpty();
    }

    @Test
    void saving_the_same_id_twice_updates_the_row_rather_than_adding_one() {
        repository.save(execution("fe-3", FormExecutionStatus.PENDING, List.of(), List.of()));
        repository.save(execution("fe-3", FormExecutionStatus.COMPLETED, List.of(),
                List.of(new Value("decision", "REFUND"))));

        assertThat(repository.findAll()).hasSize(1);
        var stored = repository.findById("fe-3").orElseThrow();
        assertThat(stored.status()).isEqualTo(FormExecutionStatus.COMPLETED);
        assertThat(stored.values()).containsExactly(new Value("decision", "REFUND"));
    }

    @Test
    void an_id_nobody_stored_is_absent_rather_than_an_error() {
        assertThat(repository.findById("never-existed")).isEmpty();
    }

    @Test
    void findAll_returns_every_execution_and_deleteAllById_removes_only_what_it_names() {
        repository.save(execution("fe-4", FormExecutionStatus.PENDING, List.of(), List.of()));
        repository.save(execution("fe-5", FormExecutionStatus.ASSIGNED, List.of(), List.of()));
        repository.save(execution("fe-6", FormExecutionStatus.CANCELLED, List.of(), List.of()));

        assertThat(repository.findAll()).extracting(FormExecution::id)
                .containsExactlyInAnyOrder("fe-4", "fe-5", "fe-6");

        repository.deleteAllById(List.of("fe-4", "fe-6"));

        assertThat(repository.findAll()).extracting(FormExecution::id).containsExactly("fe-5");
    }

    private static FormExecution execution(String id, FormExecutionStatus status,
                                           List<Variable> variables, List<Value> values) {
        return io.mateu.workflow.domain.TaskFixtures.task(id, "checkin", "process-1", "step-1", "exec-1", status,
                "alice", "reception", variables, values);
    }
}
