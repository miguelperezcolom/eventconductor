package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FormExecutionEntityRepository extends JpaRepository<FormExecutionEntity, String> {

    @Query("SELECT f FROM FormExecutionEntity f WHERE f.status IN :statusNames AND (f.userId IS NULL OR f.userId = '' OR f.userId = :userId)")
    Page<FormExecutionEntity> findByStatusAndUser(
            @Param("statusNames") List<String> statusNames,
            @Param("userId") String userId,
            Pageable pageable
    );

}
