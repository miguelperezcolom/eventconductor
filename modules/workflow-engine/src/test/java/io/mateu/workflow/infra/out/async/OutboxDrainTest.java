package io.mateu.workflow.infra.out.async;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The outbox drain — the engine's at-least-once boundary — which nothing exercised directly.
 *
 * <p>Its ordering is what makes the guarantee: deliver first, mark Sent afterwards, all in one
 * transaction, so a crash in between rolls back and another pod redelivers. Marking first would
 * lose messages, and the difference between the two is invisible to every test that only looks at
 * a process reaching COMPLETED.
 *
 * <p>The ordering was also, on its own, not enough. The send used to be asynchronous with its
 * return value discarded, so a broker that was refusing still produced a row marked Sent: during a
 * ninety-second outage, 71 of 642,912 messages were lost that way. What closed it is that a failed
 * delivery now <em>throws</em> — so the case worth pinning here is the negative one, that a
 * publisher which throws leaves its row Pending and the pass reports it as unsettled.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxDrainTest {

    @Mock OutboxMessageEntityRepository repository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DbLockDialect dbLockDialect;
    @Mock TransactionTemplate transactionTemplate;

    private OutboxDrain drain() {
        // The transaction is the unit of the guarantee, not of this test: run the callback inline
        // so what is asserted is what the callback did, commit or rollback being the caller's.
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));
        when(dbLockDialect.claimPendingOutboxSql()).thenReturn("select id from outbox ...");
        return new OutboxDrain(repository, WorkflowTracing.NOOP, jdbcTemplate, dbLockDialect,
                transactionTemplate);
    }

    private void claims(String... ids) {
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(ids));
    }

    private static OutboxMessageEntity message(String id, DomainEvent event) {
        return new OutboxMessageEntity(id, LocalDateTime.now(), "Pending",
                event.getClass().getName(), JsonSerializer.toJson(event), null);
    }

    private static OutboxMessageEntity poison(String id) {
        // A messageType outside io.mateu.* never loads — the allowlist that stops a tampered row
        // from naming an arbitrary class.
        return new OutboxMessageEntity(id, LocalDateTime.now(), "Pending",
                "java.lang.Runtime", "{}", null);
    }

    private static ProcessCreated event(String processId) {
        return new ProcessCreated(processId, List.of());
    }

    @Test
    void anEmptyOutboxClaimsNothingAndTouchesNoRows() {
        claims();

        var result = drain().drain(100, e -> {
            throw new AssertionError("nothing to deliver");
        });

        assertThat(result.claimed()).isZero();
        assertThat(result.settled()).isZero();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void aDeliveredMessageIsMarkedSent() {
        claims("m-1");
        when(repository.findAllById(List.of("m-1"))).thenReturn(List.of(message("m-1", event("p-1"))));
        var delivered = new ArrayList<DomainEvent>();

        var result = drain().drain(100, delivered::add);

        assertThat(delivered).singleElement()
                .isInstanceOfSatisfying(ProcessCreated.class,
                        e -> assertThat(e.processId()).isEqualTo("p-1"));
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.settled()).isEqualTo(1);
        assertThat(savedStatuses()).containsExactly("Sent");
    }

    /**
     * The regression test for the messages lost during the broker outage. A refusal must leave the
     * row exactly as it was, so the next pass delivers it again; anything that marks it Sent here
     * loses it silently, which is precisely what used to happen.
     */
    @Test
    void aRefusedDeliveryLeavesTheRowPendingAndTheMessageUnsettled() {
        claims("m-1");
        when(repository.findAllById(List.of("m-1"))).thenReturn(List.of(message("m-1", event("p-1"))));

        var result = drain().drain(100, e -> {
            throw new IllegalStateException("broker refused the record");
        });

        assertThat(result.claimed()).isEqualTo(1);
        // Claimed but not settled — the signal a looping caller needs to stop rather than spin.
        assertThat(result.settled()).isZero();
        verify(repository, never()).saveAll(any());
    }

    /**
     * A payload that cannot be deserialized can never succeed. Retrying it for ever would keep the
     * batch full of a message that will not move and starve everything behind it, so it is parked
     * as Error where an operator can see it and replay it by hand.
     */
    @Test
    void aPoisonPayloadIsParkedAsErrorRatherThanRetriedForever() {
        claims("m-1");
        when(repository.findAllById(List.of("m-1"))).thenReturn(List.of(poison("m-1")));
        var delivered = new ArrayList<DomainEvent>();

        var result = drain().drain(100, delivered::add);

        assertThat(delivered).isEmpty();
        assertThat(result.settled()).isEqualTo(1);
        assertThat(savedStatuses()).containsExactly("Error");
    }

    /** One bad message must not hold up the rest of its batch, in either direction. */
    @Test
    void aMixedBatchSettlesWhatItCanAndLeavesTheRestPending() {
        claims("good", "poison", "refused");
        when(repository.findAllById(List.of("good", "poison", "refused"))).thenReturn(List.of(
                message("good", event("p-good")),
                poison("poison"),
                message("refused", event("p-refused"))));

        var result = drain().drain(100, e -> {
            if (e instanceof ProcessCreated created && "p-refused".equals(created.processId())) {
                throw new IllegalStateException("broker refused the record");
            }
        });

        assertThat(result.claimed()).isEqualTo(3);
        assertThat(result.settled()).isEqualTo(2);
        assertThat(savedIds()).containsExactlyInAnyOrder("good", "poison");
        assertThat(savedStatuses()).containsExactlyInAnyOrder("Sent", "Error");
    }

    /**
     * The claim holds a row lock for as long as the transaction runs, so the batch size is really a
     * bound on how long one pod can keep the others off those rows. It has to reach the query.
     */
    @Test
    void theBatchSizeBoundsTheClaim() throws Exception {
        claims();

        drain().drain(25, e -> {});

        var setter = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbcTemplate).query(anyString(), setter.capture(), any(RowMapper.class));
        var statement = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        setter.getValue().setValues(statement);
        verify(statement).setInt(1, 25);
    }

    /**
     * A transaction manager that rolls back returns null from execute(). Reporting that as "nothing
     * claimed, nothing settled" is what stops a looping caller from treating it as progress.
     */
    @Test
    void aRolledBackPassReportsNoProgress() {
        when(transactionTemplate.execute(any())).thenReturn(null);
        when(dbLockDialect.claimPendingOutboxSql()).thenReturn("select id from outbox ...");
        var drain = new OutboxDrain(repository, WorkflowTracing.NOOP, jdbcTemplate, dbLockDialect,
                transactionTemplate);

        var result = drain.drain(100, e -> {});

        assertThat(result.claimed()).isZero();
        assertThat(result.settled()).isZero();
    }

    @SuppressWarnings("unchecked")
    private List<OutboxMessageEntity> saved() {
        var captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        var saved = new ArrayList<OutboxMessageEntity>();
        ((Iterable<OutboxMessageEntity>) captor.getValue()).forEach(saved::add);
        return saved;
    }

    private List<String> savedStatuses() {
        return saved().stream().map(OutboxMessageEntity::getStatus).toList();
    }

    private List<String> savedIds() {
        return saved().stream().map(OutboxMessageEntity::getId).toList();
    }
}
