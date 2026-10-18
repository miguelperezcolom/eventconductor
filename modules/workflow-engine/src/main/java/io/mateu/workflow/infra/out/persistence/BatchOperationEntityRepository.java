package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BatchOperationEntityRepository extends JpaRepository<BatchOperationEntity, String> {

    @Query("select b from BatchOperationEntity b order by b.createdAt desc")
    List<BatchOperationEntity> findRecent(Pageable pageable);

    @Query("select b from BatchOperationEntity b where b.status in :statuses order by b.createdAt asc")
    List<BatchOperationEntity> findByStatusOldestFirst(@Param("statuses") Collection<String> statuses, Pageable pageable);

    /** The row, locked for the rest of the transaction: how a cancellation and the runner's progress serialise. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BatchOperationEntity b where b.id = :id")
    Optional<BatchOperationEntity> findForUpdate(@Param("id") String id);
}
