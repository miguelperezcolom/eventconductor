package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One process waiting for a lock another process holds. On release the earliest row for the key —
 * ordered by {@link #enqueuedAt} — is promoted to holder, which is what makes admission FIFO.
 *
 * <p>Declared as an entity for {@code ddl-auto}; Flyway {@code V29} creates the same table for
 * migration-driven deployments.
 */
@Entity
@Table(name = "process_lock_waiter", indexes = {
        // The release path pops the earliest waiter for a key; the ordering must be covered.
        @Index(name = "idx_process_lock_waiter_queue", columnList = "lock_name, lock_key, enqueued_at"),
        // releaseAll(processId) drops this process from every queue it waits in.
        @Index(name = "idx_process_lock_waiter_process", columnList = "process_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessLockWaiterEntity {

    @Id
    private String id;

    private String lockName;

    private String lockKey;

    private String processId;

    /** The waiting step execution for a step-level lock, or null for a process-level one. */
    private String stepExecutionId;

    private LocalDateTime enqueuedAt;
}
