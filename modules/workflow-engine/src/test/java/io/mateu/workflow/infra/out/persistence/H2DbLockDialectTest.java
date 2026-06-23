package io.mateu.workflow.infra.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class H2DbLockDialectTest {

    private H2DbLockDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new H2DbLockDialect();
    }

    @Test
    void tryLockSucceedsFirstTime() throws Exception {
        Connection con = mock(Connection.class);
        assertThat(dialect.tryLock(con, 42L)).isTrue();
    }

    @Test
    void tryLockFailsWhenAlreadyLocked() throws Exception {
        Connection con = mock(Connection.class);
        dialect.tryLock(con, 99L);
        assertThat(dialect.tryLock(con, 99L)).isFalse();
    }

    @Test
    void unlockReleasesLock() throws Exception {
        Connection con = mock(Connection.class);
        dialect.tryLock(con, 77L);
        dialect.unlock(con, 77L);
        assertThat(dialect.tryLock(con, 77L)).isTrue();
    }

    @Test
    void unlockOnNotLockedIdDoesNotThrow() throws Exception {
        Connection con = mock(Connection.class);
        dialect.unlock(con, 999L);
    }
}
