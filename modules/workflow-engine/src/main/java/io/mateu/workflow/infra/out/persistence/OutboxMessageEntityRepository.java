package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxMessageEntityRepository extends JpaRepository<OutboxMessageEntity, String> {
    List<OutboxMessageEntity> findByStatus(String status);

    /** Bounded, oldest first — the relays must never load the whole pending outbox. */
    List<OutboxMessageEntity> findByStatusOrderByTimestamp(String status, Pageable pageable);

    long countByStatus(String status);
}
