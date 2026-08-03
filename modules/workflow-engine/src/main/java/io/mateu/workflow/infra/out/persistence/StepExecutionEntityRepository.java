package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Live steps with no deadline, waiting since before the given moment, <b>of the kinds for
     * which that is a fault</b>. Served by {@code idx_step_exec_status}, and it counts rather than
     * loads: the answer is a number for a gauge, and the whole point of the query is that the set
     * it counts may be large.
     *
     * <p>The type filter is what makes the number mean anything. Without it this counted every
     * USER_TASK and WAIT_FOR_MESSAGE in the system, because those have no deadline for the best of
     * reasons — a person takes days, a message catch waits indefinitely, and
     * {@code StepTimeoutDefaults} refuses to invent a deadline for either. So any deployment with
     * human tasks reported a permanently non-zero "work that will never finish", once a minute, on
     * every pod. The gauge documented as the one to alert on could not be alerted on.
     *
     * <p>ACTION and RULE are the machine work: a request went to a worker and an answer is owed.
     * Exactly the two types the fallback deadline is applied to, and for the same reason. A null
     * type is a row written before the column existed and is counted, because the alternative is
     * silence about the steps most likely to be stuck — the ones already in flight through an
     * upgrade. Those age out as their processes finish.
     */
    @Query("""
            select count(s) from StepExecutionEntity s
            where s.status in :statuses
              and s.deadlineAt is null
              and s.startedAt < :startedBefore
              and (s.stepType in :types or s.stepType is null)
            """)
    long countStalled(@Param("statuses") List<String> statuses,
                      @Param("startedBefore") LocalDateTime startedBefore,
                      @Param("types") List<String> types);
}
