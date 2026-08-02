package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface StepExecutionEntityRepository extends JpaRepository<StepExecutionEntity, String> {
    List<StepExecutionEntity> findAllByProcessIdOrderByOrder(String processId);
    List<StepExecutionEntity> findAllByStatusIn(List<String> statuses);
    List<StepExecutionEntity> findAllByProcessIdAndStatusIn(String processId, List<String> statuses);
    List<StepExecutionEntity> findAllByStatusInAndDeadlineAtLessThanEqual(List<String> statuses, LocalDateTime deadline);
    List<StepExecutionEntity> findAllByStatusInAndStartedAtIsNotNullAndDeadlineAtIsNull(List<String> statuses);
}
