package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface StepExecutionEntityRepository extends JpaRepository<StepExecutionEntity, String> {
    List<StepExecutionEntity> findAllByProcessIdOrderByOrder(String processId);
    List<StepExecutionEntity> findAllByStatusIn(List<String> statuses);
    List<StepExecutionEntity> findAllByProcessIdAndStatusIn(String processId, List<String> statuses);
    List<StepExecutionEntity> findAllByStatusInAndDeadlineAtLessThanEqual(List<String> statuses, LocalDateTime deadline);
    List<StepExecutionEntity> findAllByProcessIdAndStatusInAndDeadlineAtLessThanEqual(String processId, List<String> statuses, LocalDateTime deadline);
    List<StepExecutionEntity> findAllByStatusAndAwaitingMessageNameAndAwaitingCorrelationKey(String status, String messageName, String correlationKey);

    /**
     * Live steps with no deadline, waiting since before the given moment. Served by
     * {@code idx_step_exec_status}, and it counts rather than loads: the answer is a number for
     * a gauge, and the whole point of the query is that the set it counts may be large.
     */
    long countByStatusInAndDeadlineAtIsNullAndStartedAtLessThan(List<String> statuses, LocalDateTime startedBefore);
}
