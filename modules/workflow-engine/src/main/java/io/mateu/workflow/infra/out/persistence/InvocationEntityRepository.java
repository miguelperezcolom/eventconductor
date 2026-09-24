package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface InvocationEntityRepository extends JpaRepository<InvocationEntity, String> {

    Optional<InvocationEntity> findByWorkflowDefinitionIdAndIdempotencyKey(String workflowDefinitionId,
                                                                           String idempotencyKey);

    Optional<InvocationEntity> findFirstByProcessId(String processId);

    @Modifying
    @Query("delete from InvocationEntity i where i.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
