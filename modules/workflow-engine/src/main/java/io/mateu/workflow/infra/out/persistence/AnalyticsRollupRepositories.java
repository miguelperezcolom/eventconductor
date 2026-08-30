package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.AnalyticsProjectionStateEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessCreatedDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessDurationDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessFinishedDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessStatusDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.StepDurationDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.StepStatusDailyEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Every query the read model needs, in one place: the windowed reads of the rollup tables, the
 * cursored reads of the raw tables the projector folds from, and the two live-overlay reads of what
 * is still in flight. All package-private — this is one feature's data access.
 *
 * <p>The windowed reads filter on the creation day and return whole rows, because a 30-day window is
 * a few dozen of them; the reducer does the last grouping in memory. The cursored source reads are
 * ordered by {@code (timestamp, id)} and paged, so the projector walks each stream in bounded
 * batches and never holds the table.
 */
final class AnalyticsRollupRepositories {

    private AnalyticsRollupRepositories() {
    }

    // ─────────────────────────── rollup tables: windowed reads + upsert (JpaRepository) ───────────────────────────

    interface ProcessCreatedDailyRepository extends JpaRepository<ProcessCreatedDailyEntity, String> {
        @Query("""
                select e from ProcessCreatedDailyEntity e
                where (cast(:from as LocalDate) is null or e.day >= :from)
                  and (cast(:to as LocalDate) is null or e.day <= :to)
                """)
        List<ProcessCreatedDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
    }

    interface ProcessFinishedDailyRepository extends JpaRepository<ProcessFinishedDailyEntity, String> {
        @Query("""
                select e from ProcessFinishedDailyEntity e
                where (cast(:from as LocalDate) is null or e.createdDay >= :from)
                  and (cast(:to as LocalDate) is null or e.createdDay <= :to)
                """)
        List<ProcessFinishedDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
    }

    interface ProcessStatusDailyRepository extends JpaRepository<ProcessStatusDailyEntity, String> {
        @Query("""
                select e from ProcessStatusDailyEntity e
                where (cast(:from as LocalDate) is null or e.createdDay >= :from)
                  and (cast(:to as LocalDate) is null or e.createdDay <= :to)
                """)
        List<ProcessStatusDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
    }

    interface ProcessDurationDailyRepository extends JpaRepository<ProcessDurationDailyEntity, String> {
        @Query("""
                select e from ProcessDurationDailyEntity e
                where (cast(:from as LocalDate) is null or e.createdDay >= :from)
                  and (cast(:to as LocalDate) is null or e.createdDay <= :to)
                """)
        List<ProcessDurationDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
    }

    interface StepStatusDailyRepository extends JpaRepository<StepStatusDailyEntity, String> {
        @Query("""
                select e from StepStatusDailyEntity e
                where (cast(:from as LocalDate) is null or e.createdDay >= :from)
                  and (cast(:to as LocalDate) is null or e.createdDay <= :to)
                """)
        List<StepStatusDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
    }

    interface StepDurationDailyRepository extends JpaRepository<StepDurationDailyEntity, String> {
        @Query("""
                select e from StepDurationDailyEntity e
                where (cast(:from as LocalDate) is null or e.createdDay >= :from)
                  and (cast(:to as LocalDate) is null or e.createdDay <= :to)
                """)
        List<StepDurationDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
    }

    interface AnalyticsProjectionStateRepository
            extends JpaRepository<AnalyticsProjectionStateEntity, Integer> {
    }

    // ─────────────────────────── projector source: cursored reads of the raw tables ───────────────────────────

    interface ProcessCreationSourceView {
        String getId();
        String getWorkflowDefinitionId();
        LocalDateTime getCreated();
    }

    interface ProcessFinishSourceView {
        String getId();
        String getWorkflowDefinitionId();
        String getStatus();
        String getName();
        LocalDateTime getCreated();
        LocalDateTime getStarted();
        LocalDateTime getFinished();
    }

    interface StepFinishSourceView {
        String getId();
        String getWorkflowDefinitionId();
        String getStepId();
        String getStatus();
        long getStepOrder();
        LocalDateTime getStartedAt();
        LocalDateTime getFinishedAt();
        LocalDateTime getProcessCreated();
    }

    interface AnalyticsSourceRepository extends Repository<ProcessEntity, String> {

        @Query("""
                select p.id as id, p.workflowDefinitionId as workflowDefinitionId, p.created as created
                from ProcessEntity p
                where p.created is not null and p.created <= :ceiling
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
                  and (s.finishedAt > :cursorTs or (s.finishedAt = :cursorTs and s.id > :cursorId))
                order by s.finishedAt, s.id
                """)
        List<StepFinishSourceView> stepFinishesAfter(@Param("cursorTs") LocalDateTime cursorTs,
                                                     @Param("cursorId") String cursorId,
                                                     @Param("ceiling") LocalDateTime ceiling,
                                                     Pageable page);
    }

    // ─────────────────────────── live overlay: what is still in flight, read straight ───────────────────────────

    interface LiveProcessStatusView {
        String getDefinitionId();
        String getStatus();
        long getCount();
        String getAnyName();
    }

    interface LiveStepStatusView {
        String getDefinitionId();
        String getStepId();
        String getStatus();
        long getCount();
        long getFirstOrder();
    }

    interface LiveOverlayRepository extends Repository<ProcessEntity, String> {

        @Query("""
                select p.workflowDefinitionId as definitionId, p.status as status,
                       count(p) as count, min(p.name) as anyName
                from ProcessEntity p
                where p.finished is null and p.created is not null
                  and (cast(:from as LocalDateTime) is null or p.created >= :from)
                  and (cast(:to as LocalDateTime) is null or p.created <= :to)
                group by p.workflowDefinitionId, p.status
                """)
        List<LiveProcessStatusView> liveProcessStatus(@Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to);

        @Query("""
                select s.workflowDefinitionId as definitionId, s.stepId as stepId, s.status as status,
                       count(s) as count, min(s.order) as firstOrder
                from StepExecutionEntity s join ProcessEntity p on p.id = s.processId
                where s.finishedAt is null and p.created is not null
                  and (cast(:from as LocalDateTime) is null or p.created >= :from)
                  and (cast(:to as LocalDateTime) is null or p.created <= :to)
                group by s.workflowDefinitionId, s.stepId, s.status
                """)
        List<LiveStepStatusView> liveStepStatus(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);
    }
}
