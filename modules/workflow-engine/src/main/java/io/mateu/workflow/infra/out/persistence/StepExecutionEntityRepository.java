package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.domain.Pageable;
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

    /**
     * One page of the step-execution listing. Selects only the columns the listing paints — never
     * {@code step_json} or {@code variables}, which are the bulk of the table — and filters, orders
     * and pages in the database.
     *
     * <p>{@code :pattern} is a pre-lowercased LIKE pattern (wildcards included) or null for "no
     * text filter". {@code :onlyErrors} spells out the two failure statuses as literals — the same
     * shorthand the process listing uses for {@code 'ERROR'} — so the planner sees a plain
     * predicate over an indexed column.
     */
    @Query(value = """
            select s.id as id, s.processId as processId, s.stepId as stepId, s.status as status,
                   s.startedAt as startedAt, s.attemptCount as attemptCount
            from StepExecutionEntity s
            where (:onlyErrors = false or s.status in ('ERROR', 'TIMEOUT'))
              and (:pattern is null
                   or lower(concat(s.id, ' ', coalesce(s.processId, ''), ' ', coalesce(s.stepId, ''))) like :pattern)
            order by s.startedAt desc nulls last
            """)
    List<StepExecutionSummaryView> searchSummaries(@Param("onlyErrors") boolean onlyErrors,
                                                   @Param("pattern") String pattern,
                                                   Pageable pageable);

    /** How many step executions the same filter matches — see {@code ProcessEntityRepository}. */
    @Query("""
            select count(s)
            from StepExecutionEntity s
            where (:onlyErrors = false or s.status in ('ERROR', 'TIMEOUT'))
              and (:pattern is null
                   or lower(concat(s.id, ' ', coalesce(s.processId, ''), ' ', coalesce(s.stepId, ''))) like :pattern)
            """)
    long countSummaries(@Param("onlyErrors") boolean onlyErrors, @Param("pattern") String pattern);

    /**
     * Every step execution whose process was created inside the window, as the columns analytics
     * reads. Joined to the process rather than filtered on the step's own
     * {@code workflowDefinitionId}, because the window analytics selects by is the process's
     * creation time, not the step's.
     */
    @Query("""
            select s.processId as processId, s.stepId as stepId, s.status as status,
                   s.order as order, s.startedAt as startedAt, s.finishedAt as finishedAt
            from StepExecutionEntity s
            join ProcessEntity p on p.id = s.processId
            where p.created is not null
              and (:createdFrom is null or p.created >= :createdFrom)
              and (:createdTo is null or p.created <= :createdTo)
            """)
    List<StepExecutionAnalyticsView> findAnalyticsRows(@Param("createdFrom") LocalDateTime createdFrom,
                                                       @Param("createdTo") LocalDateTime createdTo);

    /** Projection backing {@link #findAnalyticsRows} — no step JSON, no variables. */
    interface StepExecutionAnalyticsView {
        String getProcessId();
        String getStepId();
        String getStatus();
        long getOrder();
        LocalDateTime getStartedAt();
        LocalDateTime getFinishedAt();
    }

    /** Projection backing {@link #searchSummaries} — the listing columns and nothing else. */
    interface StepExecutionSummaryView {
        String getId();
        String getProcessId();
        String getStepId();
        String getStatus();
        LocalDateTime getStartedAt();
        int getAttemptCount();
    }
}
