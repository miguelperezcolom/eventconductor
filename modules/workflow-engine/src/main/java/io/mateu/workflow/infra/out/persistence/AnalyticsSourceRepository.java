package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessCreationSourceView;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessFinishSourceView;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.StepFinishSourceView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

interface AnalyticsSourceRepository extends Repository<ProcessEntity, String> {

    @Query("""
            select p.id as id, p.workflowDefinitionId as workflowDefinitionId, p.created as created
            from ProcessEntity p
            where p.created is not null and p.created <= :ceiling
              and p.testRun = false
              and (p.created > :cursorTs or (p.created = :cursorTs and p.id > :cursorId))
            order by p.created, p.id
            """)
    List<ProcessCreationSourceView> processCreationsAfter(@Param("cursorTs") LocalDateTime cursorTs,
                                                          @Param("cursorId") String cursorId,
                                                          @Param("ceiling") LocalDateTime ceiling,
                                                          Pageable page);

    @Query("""
            select p.id as id, p.workflowDefinitionId as workflowDefinitionId, p.status as status,
                   p.name as name, p.created as created, p.started as started, p.finished as finished
            from ProcessEntity p
            where p.finished is not null and p.finished <= :ceiling
              and p.testRun = false
              and (p.finished > :cursorTs or (p.finished = :cursorTs and p.id > :cursorId))
            order by p.finished, p.id
            """)
    List<ProcessFinishSourceView> processFinishesAfter(@Param("cursorTs") LocalDateTime cursorTs,
                                                       @Param("cursorId") String cursorId,
                                                       @Param("ceiling") LocalDateTime ceiling,
                                                       Pageable page);

    @Query("""
            select s.id as id, s.workflowDefinitionId as workflowDefinitionId, s.stepId as stepId,
                   s.status as status, s.order as stepOrder, s.startedAt as startedAt,
                   s.finishedAt as finishedAt, p.created as processCreated
            from StepExecutionEntity s join ProcessEntity p on p.id = s.processId
            where s.finishedAt is not null and s.finishedAt <= :ceiling
              and p.testRun = false
              and (s.finishedAt > :cursorTs or (s.finishedAt = :cursorTs and s.id > :cursorId))
              and not (s.parentExecutionId is null and s.scopeJson is not null)
            order by s.finishedAt, s.id
            """)
    List<StepFinishSourceView> stepFinishesAfter(@Param("cursorTs") LocalDateTime cursorTs,
                                                 @Param("cursorId") String cursorId,
                                                 @Param("ceiling") LocalDateTime ceiling,
                                                 Pageable page);

    /**
     * Finishes at or before the cursor that were never counted — committed after the cursor had
     * already passed their {@code finished} (a long transaction, a pod whose clock runs behind). Only
     * a window behind the cursor is looked at, and the ledger keeps out what is already counted.
     */
    @Query("""
            select p.id as id, p.workflowDefinitionId as workflowDefinitionId, p.status as status,
                   p.name as name, p.created as created, p.started as started, p.finished as finished
            from ProcessEntity p
            where p.finished is not null and p.created is not null and p.finished >= :from
              and p.testRun = false
              and (p.finished < :cursorTs or (p.finished = :cursorTs and p.id <= :cursorId))
              and not exists (select c.processId from CountedProcessEntity c where c.processId = p.id)
            order by p.finished, p.id
            """)
    List<ProcessFinishSourceView> uncountedProcessFinishesBehind(@Param("from") LocalDateTime from,
                                                                 @Param("cursorTs") LocalDateTime cursorTs,
                                                                 @Param("cursorId") String cursorId,
                                                                 Pageable page);

    /** {@link #uncountedProcessFinishesBehind} for step executions. */
    @Query("""
            select s.id as id, s.workflowDefinitionId as workflowDefinitionId, s.stepId as stepId,
                   s.status as status, s.order as stepOrder, s.startedAt as startedAt,
                   s.finishedAt as finishedAt, p.created as processCreated
            from StepExecutionEntity s join ProcessEntity p on p.id = s.processId
            where s.finishedAt is not null and p.created is not null and s.finishedAt >= :from
              and p.testRun = false
              and (s.finishedAt < :cursorTs or (s.finishedAt = :cursorTs and s.id <= :cursorId))
              and not (s.parentExecutionId is null and s.scopeJson is not null)
              and not exists (select c.stepExecutionId from CountedStepEntity c where c.stepExecutionId = s.id)
            order by s.finishedAt, s.id
            """)
    List<StepFinishSourceView> uncountedStepFinishesBehind(@Param("from") LocalDateTime from,
                                                           @Param("cursorTs") LocalDateTime cursorTs,
                                                           @Param("cursorId") String cursorId,
                                                           Pageable page);
}
