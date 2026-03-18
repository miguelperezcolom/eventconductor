package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxMessageEntityRepository extends JpaRepository<OutboxMessageEntity, String> {
    List<OutboxMessageEntity> findByStatus(String status);
}
