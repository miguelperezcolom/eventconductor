package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProcessUpdateEntityRepository extends JpaRepository<ProcessUpdateEntity, String> {

    Optional<ProcessUpdateEntity> findByProcessIdAndIdempotencyKey(String processId, String idempotencyKey);

    List<ProcessUpdateEntity> findByProcessIdOrderByRequestedAtAscCompletedAtAsc(String processId);

    @Query("select u from ProcessUpdateEntity u where u.id in :ids and u.status <> 'REQUESTED'")
    List<ProcessUpdateEntity> findFinishedAmong(Collection<String> ids);

    @Modifying
    @Query("update ProcessUpdateEntity u set u.idempotencyKey = u.id, u.expiresAt = null"
            + " where u.expiresAt is not null and u.expiresAt < :now")
    int expireKeys(LocalDateTime now);

    @Modifying
    @Query("delete from ProcessUpdateEntity u where u.processId in :processIds")
    int deleteByProcessIds(Collection<String> processIds);
}
