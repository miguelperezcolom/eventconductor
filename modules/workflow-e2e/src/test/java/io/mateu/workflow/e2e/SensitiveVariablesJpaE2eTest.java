package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sensitive variables on the durable path (embedded + JPA on H2): the worker still gets plaintext and the
 * process still completes through the outbox, while no table — process, step, outbox, log — holds the card
 * number or the authorization code in clear. Read raw, with SQL, as an attacker with the database would.
 */
class SensitiveVariablesJpaE2eTest extends AbstractJpaE2eTest {

    static io.mateu.workflow.dtos.Variable dto(String name, String value) {
        return new io.mateu.workflow.dtos.Variable(name, value);
    }

    static final String CARD = "4111111111111111";

    @Autowired JdbcTemplate jdbc;

    @Test
    void nothingAtRestHoldsTheSensitiveValuesInClear() {
        worker.on("charge", TestWorker.succeed(var("authCode", "AUTH-7731")));
        worker.on("visa", (request, callback, n) -> callback.fail("could not book card " + CARD));

        createProcess("sensitive-payment", "jpa-pay-1", dto("cardNumber", CARD), dto("amount", "120"));
        awaitStatus("jpa-pay-1", ProcessStatus.ERROR);

        // The worker, in process, got plaintext; the guard read it.
        assertThat(worker.received().getFirst().variables()).contains(dto("cardNumber", CARD));
        assertThat(worker.invocationsOf("visa")).isEqualTo(1);
        // The repositories give the engine plaintext back.
        assertThat(process("jpa-pay-1").getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("cardNumber", CARD),
                        new io.mateu.workflow.domain.aggregates.Variable("authCode", "AUTH-7731"));

        var processRow = jdbc.queryForObject("select variables from process_entity where business_key = 'jpa-pay-1'", String.class);
        assertThat(processRow).contains("ec:enc:v1:e2e-test:").contains("\"amount\"").contains("120")
                .doesNotContain(CARD).doesNotContain("AUTH-7731");
        assertThat(jdbc.queryForList("select variables from step_execution_entity", String.class))
                .isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row)).doesNotContain(CARD).doesNotContain("AUTH-7731"));
        assertThat(jdbc.queryForList("select payload from outbox_message_entity", String.class))
                .isNotEmpty().allSatisfy(row -> assertThat(row).doesNotContain(CARD).doesNotContain("AUTH-7731"))
                .anySatisfy(row -> assertThat(row).contains("ec:enc:v1:"));
        assertThat(jdbc.queryForList("select message from log_message_entity", String.class))
                .allSatisfy(row -> assertThat(String.valueOf(row)).doesNotContain(CARD))
                .anySatisfy(row -> assertThat(String.valueOf(row)).contains("could not book card ••••"));
    }
}
