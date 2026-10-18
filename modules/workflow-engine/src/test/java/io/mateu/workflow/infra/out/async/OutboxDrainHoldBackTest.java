package io.mateu.workflow.infra.out.async;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drain's use of {@link OutboxKeyOrder}: claimed rows that would overtake an older pending event
 * of their process held elsewhere are not delivered, stay Pending, and are reported as held back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxDrainHoldBackTest {

    private static final String CLAIM = "claim";
    private static final LocalDateTime T0 = LocalDateTime.now().minusMinutes(1);

    @Mock OutboxMessageEntityRepository repository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DbLockDialect dbLockDialect;
    @Mock TransactionTemplate transactionTemplate;

    private OutboxDrain drain;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));
        when(dbLockDialect.claimPendingOutboxSql()).thenReturn(CLAIM);
        drain = new OutboxDrain(repository, WorkflowTracing.NOOP, WorkflowMetrics.NOOP, jdbcTemplate,
                dbLockDialect, transactionTemplate);
    }

    private static OutboxMessageEntity row(String id, String processId, int seconds) {
        DomainEvent event = new ProcessCreated(processId, List.of());
        var row = new OutboxMessageEntity(id, T0.plusSeconds(seconds), "Pending",
                event.getClass().getName(), JsonSerializer.toJson(event), null);
        row.setPartitionKey(processId);
        return row;
    }

    @SuppressWarnings("unchecked")
    private void claimed(List<String> ids, List<OutboxMessageEntity> rows, List<OutboxKeyOrder.Row> pending) {
        when(jdbcTemplate.query(eq(CLAIM), any(PreparedStatementSetter.class), any(RowMapper.class))).thenReturn(ids);
        when(repository.findAllById(ids)).thenReturn(rows);
        when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.contains("partition_key IN")),
                any(PreparedStatementSetter.class), any(RowMapper.class))).thenReturn((List) pending);
    }

    private static OutboxKeyOrder.Row pending(OutboxMessageEntity row) {
        return new OutboxKeyOrder.Row(row.getId(), row.getPartitionKey(), row.getTimestamp());
    }

    @Test
    void rowsBehindAnOlderEventHeldElsewhereAreNotSentAndAreReported() {
        var p1 = row("p1", "P", 1); // held by another pod — not in this claim
        var p2 = row("p2", "P", 2);
        var q1 = row("q1", "Q", 3);
        claimed(List.of("p2", "q1"), List.of(p2, q1), List.of(pending(p1), pending(p2), pending(q1)));
        var delivered = new ArrayList<String>();

        var result = drain.drain(10, event -> delivered.add(event.partitionKey()));

        assertThat(delivered).containsExactly("Q");
        assertThat(result.claimed()).isEqualTo(2);
        assertThat(result.settled()).isEqualTo(1);
        assertThat(result.heldBack()).isEqualTo(1);
        assertThat(result.attempted()).isEqualTo(1);
        assertThat(p2.getStatus()).isEqualTo("Pending");
    }

    @Test
    void aRunThatStartsAtItsHeadIsSentWhole() {
        var p1 = row("p1", "P", 1);
        var p2 = row("p2", "P", 2);
        claimed(List.of("p1", "p2"), List.of(p2, p1), List.of(pending(p1), pending(p2)));
        var delivered = new ArrayList<DomainEvent>();

        var result = drain.drain(10, delivered::add);

        assertThat(delivered).hasSize(2);
        assertThat(result.heldBack()).isZero();
        assertThat(result.settled()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unkeyedRowsNeedNoRead() {
        var u = row("u", "P", 1);
        u.setPartitionKey(null);
        when(jdbcTemplate.query(eq(CLAIM), any(PreparedStatementSetter.class), any(RowMapper.class))).thenReturn(List.of("u"));
        when(repository.findAllById(List.of("u"))).thenReturn(List.of(u));

        var result = drain.drain(10, event -> {});

        assertThat(result.settled()).isEqualTo(1);
        verify(jdbcTemplate, times(1)).query(any(String.class), any(PreparedStatementSetter.class), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keysAreReadInChunksSoNoInListOutgrowsADatabasesLimit() {
        int count = OutboxKeyOrder.MAX_KEYS_PER_STATEMENT + 1;
        var ids = new ArrayList<String>();
        var rows = new ArrayList<OutboxMessageEntity>();
        for (int i = 0; i < count; i++) {
            ids.add("m" + i);
            rows.add(row("m" + i, "key-" + i, i));
        }
        claimed(ids, rows, List.of());

        drain.drain(count, event -> {});

        verify(jdbcTemplate).query(eq(OutboxKeyOrder.pendingOfKeysSql(OutboxKeyOrder.MAX_KEYS_PER_STATEMENT)),
                any(PreparedStatementSetter.class), any(RowMapper.class));
        verify(jdbcTemplate).query(eq(OutboxKeyOrder.pendingOfKeysSql(1)),
                any(PreparedStatementSetter.class), any(RowMapper.class));
        verify(repository, never()).saveAll(any());
    }
}
