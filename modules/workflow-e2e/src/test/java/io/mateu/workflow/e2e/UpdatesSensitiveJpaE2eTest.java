package io.mateu.workflow.e2e;

import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.ProcessUpdatesHttp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static io.mateu.workflow.e2e.support.ProcessUpdatesHttp.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates of a process with sensitive variables, on the durable path: the card an update writes is sealed in
 * the process row and in the update's record (read raw, as an attacker with the database would), the
 * fingerprint is keyed, every reader sees masks — and a reply allowed to read the card is given in clear only
 * to the request that asked for it.
 */
class UpdatesSensitiveJpaE2eTest extends AbstractJpaE2eTest {

    static final String CARD = "4111111111111111";

    @Autowired ProcessUpdateService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theCardIsNeverAtRestInClearAndReadersSeeMasks() throws Exception {
        var http = new ProcessUpdatesHttp(service);
        createProcess("sensitive-updates", "sens-upd-1");
        awaitStatus("sens-upd-1", ProcessStatus.RUNNING);
        var id = process("sens-upd-1").getId();

        var set = http.update(id, "setCard", "{\"card\": \"" + CARD + "\"}", "card-key", "wait=10");
        assertThat(set.getStatus()).isEqualTo(200);
        assertThat(json(set).path("result").path("last4").asText()).isEqualTo("1111");
        assertThat(json(set).path("payload").path("card").asText()).isEqualTo("••••");
        assertThat(process("sens-upd-1").getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("cardNumber", CARD));

        // A retry is verified against the keyed fingerprint.
        var retried = http.update(id, "setCard", "{\"card\": \"" + CARD + "\"}", "card-key", "wait=10");
        assertThat(json(retried).path("updateId").asText()).isEqualTo(json(set).path("updateId").asText());

        var shown = http.update(id, "showCard", null, null, "wait=10");
        assertThat(shown.getStatus()).isEqualTo(200);
        assertThat(json(shown).path("result").path("cardNumber").asText()).isEqualTo(CARD);
        var read = http.get("/workflow/api/updates/" + json(shown).path("updateId").asText(), null);
        assertThat(read.getContentAsString()).doesNotContain(CARD).contains("••••");
        assertThat(http.get("/workflow/api/processes/" + id + "/updates", null).getContentAsString())
                .doesNotContain(CARD).contains("••••");

        assertThat(jdbc.queryForObject("select variables from process_entity where id = ?", String.class, id))
                .doesNotContain(CARD).contains("ec:enc:v1:");
        assertThat(jdbc.queryForList("select payload from process_update where process_id = ?", String.class, id))
                .allSatisfy(payload -> assertThat(String.valueOf(payload)).doesNotContain(CARD));
        assertThat(jdbc.queryForList("select result from process_update where process_id = ?", String.class, id))
                .allSatisfy(result -> assertThat(String.valueOf(result)).doesNotContain(CARD));
        assertThat(jdbc.queryForList("select request_hash from process_update where process_id = ?", String.class, id))
                .allSatisfy(hash -> assertThat(hash).startsWith("h1:"));
        assertThat(jdbc.queryForList("select message from log_message_entity where process_id = ?", String.class, id))
                .allSatisfy(line -> assertThat(String.valueOf(line)).doesNotContain(CARD));
    }
}
