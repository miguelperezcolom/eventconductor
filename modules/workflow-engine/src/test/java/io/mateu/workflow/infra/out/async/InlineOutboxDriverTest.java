package io.mateu.workflow.infra.out.async;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The inline driver against a real (H2) outbox table: it handles the claimed rows of its process in
 * order and marks them Sent, stops at its budget or on a retryable failure and hands the rest back,
 * parks a message that can never be handled, and never touches another pod's claims.
 */
class InlineOutboxDriverTest {

    JdbcTemplate jdbc;
    ProcessDomainEventUseCase handler = mock(ProcessDomainEventUseCase.class);
    List<String> handled = new ArrayList<>();
    InlineOutboxDriver driver;
    InlineClaimSweeper sweeper;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:inline-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE outbox_message_entity (id VARCHAR(64) PRIMARY KEY, timestamp TIMESTAMP,"
                + " status VARCHAR(32), message_type VARCHAR(255), payload TEXT, trace_parent VARCHAR(64),"
                + " partition_key VARCHAR(255), claimed_by VARCHAR(64), claim_until TIMESTAMP)");
        doAnswer(invocation -> {
            var event = ((ProcessDomainEventCommand) invocation.getArgument(0)).event();
            handled.add(((ProcessCreated) event).processId() + "#" + handled.size());
            return null;
        }).when(handler).handle(any());
        var providers = new StaticListableBeanFactory();
        providers.addBean("handler", handler);
        driver = new InlineOutboxDriver(jdbc, providers.getBeanProvider(ProcessDomainEventUseCase.class),
                new OutboxSignal(), WorkflowMetrics.NOOP);
        driver.threads = 1;
        driver.start();
        sweeper = new InlineClaimSweeper(jdbc, new OutboxSignal());
    }

    @AfterEach
    void tearDown() {
        driver.stop();
    }

    private void row(String id, String processId, String status, String pod, LocalDateTime claimUntil, int second) {
        jdbc.update("INSERT INTO outbox_message_entity (id, timestamp, status, message_type, payload, partition_key,"
                        + " claimed_by, claim_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0, second)), status,
                ProcessCreated.class.getName(), JsonSerializer.toJson(new ProcessCreated(processId, List.of())),
                processId, pod, claimUntil == null ? null : Timestamp.valueOf(claimUntil));
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM outbox_message_entity WHERE id = ?", String.class, id);
    }

    private LocalDateTime later() {
        return LocalDateTime.now().plusMinutes(5);
    }

    @Test
    void handlesItsClaimedRowsInOrderAndLeavesEverythingElseAlone() {
        row("r2", "p1", "InlineClaimed", driver.POD, later(), 2);
        row("r1", "p1", "InlineClaimed", driver.POD, later(), 1);
        row("other-pod", "p1", "InlineClaimed", "pod-else", later(), 3);
        row("pending", "p1", "Pending", null, null, 4);
        row("other-process", "p2", "InlineClaimed", driver.POD, later(), 5);

        driver.drive("p1");

        assertThat(handled).containsExactly("p1#0", "p1#1");
        assertThat(status("r1")).isEqualTo("Sent");
        assertThat(status("r2")).isEqualTo("Sent");
        assertThat(status("other-pod")).isEqualTo("InlineClaimed");
        assertThat(status("pending")).isEqualTo("Pending");
        assertThat(status("other-process")).isEqualTo("InlineClaimed");
    }

    @Test
    void theBudgetStopsTheDriveAndTheRestGoesBackToTheRelay() {
        driver.maxSteps = 1;
        row("r1", "p1", "InlineClaimed", driver.POD, later(), 1);
        row("r2", "p1", "InlineClaimed", driver.POD, later(), 2);

        driver.drive("p1");

        assertThat(status("r1")).isEqualTo("Sent");
        assertThat(status("r2")).isEqualTo("Pending");
    }

    @Test
    void aRetryableFailureHandsTheRowBack() {
        doAnswer(invocation -> { throw new org.springframework.dao.TransientDataAccessResourceException("db blip"); })
                .when(handler).handle(any());
        row("r1", "p1", "InlineClaimed", driver.POD, later(), 1);

        driver.drive("p1");

        assertThat(status("r1")).isEqualTo("Pending");
    }

    @Test
    void aMessageThatCanNeverBeHandledIsParked() {
        doAnswer(invocation -> { throw new IllegalArgumentException("bad"); }).when(handler).handle(any());
        row("r1", "p1", "InlineClaimed", driver.POD, later(), 1);
        jdbc.update("INSERT INTO outbox_message_entity (id, timestamp, status, message_type, payload, partition_key,"
                + " claimed_by, claim_until) VALUES ('poison', ?, 'InlineClaimed', 'java.lang.Runtime', '{}', 'p1', ?, ?)",
                Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0, 0)), driver.POD, Timestamp.valueOf(later()));

        driver.drive("p1");

        assertThat(status("poison")).isEqualTo("Error");
        assertThat(status("r1")).isEqualTo("Error");
    }

    @Test
    void aSlotIsReservedClaimsDuringCreationAndIsGivenBack() {
        var slot = driver.reserve("p9");
        assertThat(slot).isNotNull();
        assertThat(driver.reserve("p10")).as("one thread, one slot").isNull();
        slot.claimDuring(() -> assertThat(InlineDrive.claimFor(new ProcessCreated("p9", List.of()))).isNotNull());
        slot.abandon();
        slot.abandon(); // idempotent
        var again = driver.reserve("p10");
        assertThat(again).isNotNull();
        row("r1", "p10", "InlineClaimed", driver.POD, later(), 1);
        again.start();
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(status("r1")).isEqualTo("Sent"));
    }

    @Test
    void switchedOffThereIsNoSlot() {
        driver.enabled = false;
        assertThat(driver.reserve("p1")).isNull();
    }

    @Test
    void theSweeperReturnsOnlyLapsedClaims() {
        row("lapsed", "p1", "InlineClaimed", "pod-dead", LocalDateTime.now().minusSeconds(1), 1);
        row("alive", "p1", "InlineClaimed", "pod-alive", later(), 2);

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(status("lapsed")).isEqualTo("Pending");
        assertThat(status("alive")).isEqualTo("InlineClaimed");
    }
}
