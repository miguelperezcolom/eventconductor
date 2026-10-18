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

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drain's half of the per-key claim: having claimed the heads of their partition keys, a pass
 * takes the rest of those keys' runs with them, so a process's next event does not wait for the
 * next poll — and it asks only for what is left of the batch, and only for keys it actually holds.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxDrainFollowersTest {

    private static final String HEADS = "heads";
    private static final String FOLLOWERS = "followers";
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
        when(dbLockDialect.claimPendingOutboxSql()).thenReturn(HEADS);
        when(dbLockDialect.claimFollowingOutboxSql(anyInt())).thenReturn(FOLLOWERS);
        drain = new OutboxDrain(repository, WorkflowTracing.NOOP, WorkflowMetrics.NOOP, jdbcTemplate,
                dbLockDialect, transactionTemplate);
    }

    private void claimed(String sql, String... ids) {
        when(jdbcTemplate.query(eq(sql), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(ids));
    }

    private static OutboxMessageEntity row(String id, String processId, int secondsAfterT0) {
        DomainEvent event = new ProcessCreated(processId, List.of());
        var row = new OutboxMessageEntity(id, T0.plusSeconds(secondsAfterT0), "Pending",
                event.getClass().getName(), JsonSerializer.toJson(event), null);
        row.setPartitionKey(processId);
        return row;
    }

    @Test
    void aPassRelaysTheWholeRunOfEveryKeyWhoseHeadItClaimed() throws Exception {
        claimed(HEADS, "p1", "q1");
        when(repository.findAllById(List.of("p1", "q1"))).thenReturn(List.of(row("p1", "P", 1), row("q1", "Q", 2)));
        // The runs come back with their heads; the drain keeps only what it does not hold yet.
        claimed(FOLLOWERS, "p1", "q1", "p2", "q2", "p3");
        when(repository.findAllById(List.of("p2", "q2", "p3")))
                .thenReturn(List.of(row("p3", "P", 5), row("q2", "Q", 4), row("p2", "P", 3)));
        var delivered = new ArrayList<String>();

        var result = drain.drain(10, event -> delivered.add(event.partitionKey()));

        assertThat(result.claimed()).isEqualTo(5);
        assertThat(result.settled()).isEqualTo(5);
        // Each key in its own order, whatever order the rows were loaded in.
        assertThat(delivered).containsExactly("P", "P", "P", "Q", "Q");

        // The runs asked for are those of the claimed keys: what is left of the batch, plus the heads.
        verify(dbLockDialect).claimFollowingOutboxSql(2);
        var setter = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbcTemplate).query(eq(FOLLOWERS), setter.capture(), any(RowMapper.class));
        var statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement);
        verify(statement).setString(1, "P");
        verify(statement).setString(2, "Q");
        verify(statement).setInt(3, 10);
    }

    @Test
    void aBatchFullOfHeadsAsksForNoFollowers() {
        claimed(HEADS, "p1", "q1");
        when(repository.findAllById(List.of("p1", "q1"))).thenReturn(List.of(row("p1", "P", 1), row("q1", "Q", 2)));

        var result = drain.drain(2, event -> {});

        assertThat(result.claimed()).isEqualTo(2);
        verify(dbLockDialect, never()).claimFollowingOutboxSql(anyInt());
    }

    @Test
    void unkeyedHeadsHaveNoFollowersToAskFor() {
        claimed(HEADS, "u1");
        var unkeyed = row("u1", "P", 1);
        unkeyed.setPartitionKey(null);
        when(repository.findAllById(List.of("u1"))).thenReturn(List.of(unkeyed));

        drain.drain(10, event -> {});

        verify(dbLockDialect, never()).claimFollowingOutboxSql(anyInt());
    }

    @Test
    void keysAreAskedForInChunksSoNoInListGrowsWithTheBatch() {
        int heads = OutboxDrain.MAX_KEYS_PER_CLAIM + 1;
        var ids = new ArrayList<String>();
        var rows = new ArrayList<OutboxMessageEntity>();
        for (int i = 0; i < heads; i++) {
            ids.add("h" + i);
            rows.add(row("h" + i, "key-" + i, i));
        }
        claimed(HEADS, ids.toArray(String[]::new));
        when(repository.findAllById(ids)).thenReturn(rows);
        claimed(FOLLOWERS);

        drain.drain(heads + 100, event -> {});

        verify(dbLockDialect).claimFollowingOutboxSql(OutboxDrain.MAX_KEYS_PER_CLAIM);
        verify(dbLockDialect).claimFollowingOutboxSql(1);
    }
}
