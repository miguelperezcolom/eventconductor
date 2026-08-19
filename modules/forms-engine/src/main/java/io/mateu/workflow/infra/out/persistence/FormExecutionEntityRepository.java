package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FormExecutionEntityRepository extends JpaRepository<FormExecutionEntity, String> {

    interface TaskSummary {
        String getId();
        String getFormId();
        String getProcessId();
        String getUserId();
        String getStatus();
    }

    @Query("""
            SELECT f FROM FormExecutionEntity f
            WHERE f.status IN :statusNames AND (f.userId IS NULL OR f.userId = '' OR f.userId = :userId)
            """)
    Page<FormExecutionEntity> findByStatusAndUser(
            @Param("statusNames") List<String> statusNames,
            @Param("userId") String userId,
            Pageable pageable
    );

    List<FormExecutionEntity> findByStatus(String status);

    /**
     * The same page {@code findByStatusAndUser} returns, without narrowing by user — for reading
     * what the forms on a page declare rather than for showing anything. Passing a null user to
     * that one would silently drop every claimed task, since {@code f.userId = :userId} is never
     * true for null.
     */
    Page<FormExecutionEntity> findByStatusIn(List<String> statusNames, Pageable pageable);

    // Projection: leaves out the variables/values TEXT columns, which can be large
    @Query("""
            SELECT f.id AS id, f.formId AS formId, f.processId AS processId, f.userId AS userId, f.status AS status
            FROM FormExecutionEntity f
            WHERE f.status IN :statusNames AND (f.userId IS NULL OR f.userId = '' OR f.userId = :userId)
            """)
    Page<TaskSummary> findTaskSummariesByStatusAndUser(
            @Param("statusNames") List<String> statusNames,
            @Param("userId") String userId,
            Pageable pageable
    );

    // The userId guard makes claiming atomic: tasks already assigned to another user are skipped
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE FormExecutionEntity f SET f.userId = :userId
            WHERE f.id IN :ids AND (f.userId IS NULL OR f.userId = '' OR f.userId = :userId)
            """)
    int claim(@Param("ids") List<String> ids, @Param("userId") String userId);

    List<FormExecutionEntity> findByStepExecutionId(String stepExecutionId);

}
