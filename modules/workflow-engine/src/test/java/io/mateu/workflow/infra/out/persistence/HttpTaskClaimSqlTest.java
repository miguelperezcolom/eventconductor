package io.mateu.workflow.infra.out.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The claim statement each dialect gives the HTTP worker queue. */
class HttpTaskClaimSqlTest {

    @Test
    void theDefaultClaimLimitsInsideTheStatementAndSkipsLockedRows() {
        var sql = new PostgresDbLockDialect().claimHttpTasksSql(3);
        assertThat(sql).contains("topic IN (?, ?, ?)", "cancelled = ?", "lease_until < ?",
                "ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED");
        assertThat(new MariaDbLockDialect().claimHttpTasksSql(1)).contains("topic IN (?)");
        assertThat(new H2DbLockDialect().claimHttpTasksSql(0)).contains("topic IN (?)");
    }

    @Test
    void oracleLimitsWithRownumOutsideTheSort() {
        var sql = new OracleDbLockDialect().claimHttpTasksSql(2);
        assertThat(sql).contains("topic IN (?, ?)", "ORDER BY created_at) WHERE ROWNUM <= ?) FOR UPDATE SKIP LOCKED")
                .doesNotContain("LIMIT");
    }
}
