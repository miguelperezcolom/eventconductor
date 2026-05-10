package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * PostgreSQL advisory-lock implementation of ProcessLockService.
 * Converts the UUID processId to a stable long key via XOR of its two 64-bit halves,
 * then delegates to pg_try_advisory_lock / pg_advisory_unlock using parameterised
 * queries (no string concatenation).
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@RequiredArgsConstructor
public class PostgresProcessLockService implements ProcessLockService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean tryLock(String processId) {
        long key = toLockKey(processId);
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, key));
    }

    @Override
    public void unlock(String processId) {
        long key = toLockKey(processId);
        jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, key);
    }

    /**
     * Converts a UUID string to a stable 64-bit lock key.
     * XOR of the two 64-bit halves gives a well-distributed long with no collisions
     * between UUIDs that differ in either half.
     */
    static long toLockKey(String processId) {
        UUID uuid = UUID.fromString(processId);
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }
}
