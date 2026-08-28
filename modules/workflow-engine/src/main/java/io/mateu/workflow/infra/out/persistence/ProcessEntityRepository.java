package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProcessEntityRepository extends JpaRepository<ProcessEntity, String> {

    Optional<ProcessEntity> findByBusinessKey(String businessKey);

    long countByStatus(String status);

    /** Process counts grouped by status — a handful of rows, for the home dashboard KPIs. */
    @Query("select p.status as key, count(p) as count from ProcessEntity p group by p.status")
    List<CountByKey> countGroupedByStatus();

    /** Process counts grouped by workflow definition id — one row per definition, for the home charts. */
    @Query("select p.workflowDefinitionId as key, count(p) as count from ProcessEntity p group by p.workflowDefinitionId")
    List<CountByKey> countGroupedByDefinition();

    /**
     * Process counts for one definition, grouped by the definition version they ran with and their
     * status — the raw material for per-version stats (total / running / completed). The version is
     * cast to string to fit the shared {@link CountByKey#getKey()} projection.
     */
    @Query("""
            select cast(p.workflowDefinitionVersion as string) as key, p.status as status, count(p) as count
            from ProcessEntity p
            where p.workflowDefinitionId = :definitionId
            group by p.workflowDefinitionVersion, p.status
            """)
    List<VersionStatusCount> countByVersionAndStatus(@Param("definitionId") String definitionId);

    /**
     * One page of the process listing. Selects only the columns the listing paints — never
     * {@code workflow_definition_json}, {@code variables} or {@code log}, which together are all but
     * a rounding error away from the whole table — and filters, orders and pages in the database.
     *
     * <p>{@code :pattern} is a pre-lowercased LIKE pattern (wildcards included) or null for "no
     * text filter"; it is matched against the same {@code name + " " + businessKey} text the
     * in-memory store searches.
     */
    @Query(value = """
            select p.id as id, p.name as name, p.status as status,
                   p.completionPercentage as completionPercentage,
                   p.created as created, p.started as started, p.finished as finished
            from ProcessEntity p
            where (:onlyErrors = false or p.status = 'ERROR')
              and (:pattern is null
                   or lower(concat(coalesce(p.name, ''), ' ', coalesce(p.businessKey, ''))) like :pattern)
              and (:definitionId is null or p.workflowDefinitionId = :definitionId)
              and (:status is null or p.status = :status)
              and (cast(:createdFrom as LocalDateTime) is null or p.created >= :createdFrom)
              and (cast(:createdTo as LocalDateTime) is null or p.created <= :createdTo)
            order by p.created desc nulls last
            """)
    List<ProcessSummaryView> searchSummaries(@Param("onlyErrors") boolean onlyErrors,
                                             @Param("pattern") String pattern,
                                             @Param("definitionId") String definitionId,
                                             @Param("status") String status,
                                             @Param("createdFrom") LocalDateTime createdFrom,
                                             @Param("createdTo") LocalDateTime createdTo,
                                             Pageable pageable);

    /**
     * How many processes the same filter matches. Separate from {@link #searchSummaries} rather
     * than derived from a {@code Page} return type, because the caller needs the total <b>before</b>
     * it can work out which page to ask for: a request past the end is answered with the last real
     * page, and there is no last page until you know how many there are.
     */
    @Query("""
            select count(p)
            from ProcessEntity p
            where (:onlyErrors = false or p.status = 'ERROR')
              and (:pattern is null
                   or lower(concat(coalesce(p.name, ''), ' ', coalesce(p.businessKey, ''))) like :pattern)
              and (:definitionId is null or p.workflowDefinitionId = :definitionId)
              and (:status is null or p.status = :status)
              and (cast(:createdFrom as LocalDateTime) is null or p.created >= :createdFrom)
              and (cast(:createdTo as LocalDateTime) is null or p.created <= :createdTo)
            """)
    long countSummaries(@Param("onlyErrors") boolean onlyErrors,
                        @Param("pattern") String pattern,
                        @Param("definitionId") String definitionId,
                        @Param("status") String status,
                        @Param("createdFrom") LocalDateTime createdFrom,
                        @Param("createdTo") LocalDateTime createdTo);

    /**
     * Every process created inside the window, as the columns analytics reads. The window bounds
     * are nullable for "unbounded"; a null {@code created} is outside every window.
     */
    @Query("""
            select p.id as id, p.name as name,
                   p.workflowDefinitionId as workflowDefinitionId, p.status as status,
                   p.created as created, p.started as started, p.finished as finished
            from ProcessEntity p
            where p.created is not null
              and (cast(:createdFrom as LocalDateTime) is null or p.created >= :createdFrom)
              and (cast(:createdTo as LocalDateTime) is null or p.created <= :createdTo)
            """)
    List<ProcessAnalyticsView> findAnalyticsRows(@Param("createdFrom") LocalDateTime createdFrom,
                                                 @Param("createdTo") LocalDateTime createdTo);

    /**
     * Analytics' counts, per definition and status, for the processes created inside the window.
     * {@code min(p.name)} rides along as the name to show when the definition itself is gone.
     */
    @Query("""
            select p.workflowDefinitionId as definitionId, p.status as status,
                   count(p) as count, min(p.name) as anyName
            from ProcessEntity p
            where p.created is not null
              and (cast(:createdFrom as LocalDateTime) is null or p.created >= :createdFrom)
              and (cast(:createdTo as LocalDateTime) is null or p.created <= :createdTo)
            group by p.workflowDefinitionId, p.status
            """)
    List<StatusCountView> aggregateStatusCounts(@Param("createdFrom") LocalDateTime createdFrom,
                                                @Param("createdTo") LocalDateTime createdTo);

    /** Analytics' per-day throughput, by creation date. */
    @Query("""
            select p.workflowDefinitionId as definitionId, cast(p.created as LocalDate) as day, count(p) as count
            from ProcessEntity p
            where p.created is not null
              and (cast(:createdFrom as LocalDateTime) is null or p.created >= :createdFrom)
              and (cast(:createdTo as LocalDateTime) is null or p.created <= :createdTo)
            group by p.workflowDefinitionId, cast(p.created as LocalDate)
            """)
    List<DayCountView> aggregateCreatedPerDay(@Param("createdFrom") LocalDateTime createdFrom,
                                              @Param("createdTo") LocalDateTime createdTo);

    /** The same, by completion date — so the window still selects on creation. */
    @Query("""
            select p.workflowDefinitionId as definitionId, cast(p.finished as LocalDate) as day, count(p) as count
            from ProcessEntity p
            where p.created is not null and p.finished is not null
              and (cast(:createdFrom as LocalDateTime) is null or p.created >= :createdFrom)
              and (cast(:createdTo as LocalDateTime) is null or p.created <= :createdTo)
            group by p.workflowDefinitionId, cast(p.finished as LocalDate)
            """)
    List<DayCountView> aggregateFinishedPerDay(@Param("createdFrom") LocalDateTime createdFrom,
                                               @Param("createdTo") LocalDateTime createdTo);

    /**
     * How long the finished processes of each definition took: the count, the total, and the
     * nearest-rank 95th percentile.
     *
     * <p>Nanoseconds, and a <b>total</b> rather than an average, so the numbers cannot drift from
     * the ones Java produced: the service divides the total exactly as {@code Duration.dividedBy}
     * always did, and a coarser unit would report zero for the many steps that take milliseconds.
     * {@code percentile_disc} returns a measured sample rather than an interpolation, which is the
     * rule the Java implementation has always applied.
     */
    @Query("""
            select p.workflowDefinitionId as definitionId,
                   count(p) as samples,
                   sum(timestampdiff(nanosecond, coalesce(p.started, p.created), p.finished)) as totalNanos,
                   percentile_disc(0.95) within group (
                       order by timestampdiff(nanosecond, coalesce(p.started, p.created), p.finished)
                   ) as p95Nanos
            from ProcessEntity p
            where p.created is not null and p.finished is not null
              and (cast(:createdFrom as LocalDateTime) is null or p.created >= :createdFrom)
              and (cast(:createdTo as LocalDateTime) is null or p.created <= :createdTo)
            group by p.workflowDefinitionId
            """)
    List<DurationView> aggregateDurations(@Param("createdFrom") LocalDateTime createdFrom,
                                          @Param("createdTo") LocalDateTime createdTo);

    /** Projection for {@link #aggregateStatusCounts}. */
    interface StatusCountView {
        String getDefinitionId();
        String getStatus();
        long getCount();
        String getAnyName();
    }

    /** Projection for the two per-day counts. */
    interface DayCountView {
        String getDefinitionId();
        java.time.LocalDate getDay();
        long getCount();
    }

    /** Projection for {@link #aggregateDurations}. */
    interface DurationView {
        String getDefinitionId();
        long getSamples();
        Long getTotalNanos();
        Long getP95Nanos();
    }

    /** Projection backing {@link #findAnalyticsRows} — no variables, no log, no definition JSON. */
    interface ProcessAnalyticsView {
        String getId();
        String getName();
        String getWorkflowDefinitionId();
        String getStatus();
        LocalDateTime getCreated();
        LocalDateTime getStarted();
        LocalDateTime getFinished();
    }

    /** Projection backing {@link #searchSummaries} — the listing columns and nothing else. */
    interface ProcessSummaryView {
        String getId();
        String getName();
        String getStatus();
        int getCompletionPercentage();
        LocalDateTime getCreated();
        LocalDateTime getStarted();
        LocalDateTime getFinished();
    }

    /** Projection for the grouped-count queries above. */
    interface CountByKey {
        String getKey();
        long getCount();
    }

    /** Projection for the per-version, per-status counts. */
    interface VersionStatusCount {
        String getKey();
        String getStatus();
        long getCount();
    }


    /**
     * Ids of processes that are RUNNING, have nothing left to run, and stopped moving before
     * {@code idleBefore}. See {@code ProcessRepository#findStalled} for what that means.
     *
     * <p>Cheap because it starts from {@code status = 'RUNNING'}, which {@code idx_process_status}
     * covers and which is a small set on a healthy deployment. The two correlated subqueries then
     * run per candidate over {@code idx_step_exec_process_status}. It stops being cheap on a
     * deployment holding tens of thousands of processes genuinely in flight — which is why it runs
     * on its own slow loop and not on the timeout scan.
     *
     * <p>{@code max(finishedAt)} is null for a process whose steps have all been created and none
     * finished; that is a process that never started moving, and it is excluded rather than
     * reported, because "stalled" is about stopping, not about not having begun.
     */
    @Query("""
            select p.id
            from ProcessEntity p
            where p.status = 'RUNNING'
              and not exists (
                  select 1 from StepExecutionEntity se
                  where se.processId = p.id and se.status in ('PENDING', 'RUNNING')
              )
              and (
                  select max(se2.finishedAt) from StepExecutionEntity se2
                  where se2.processId = p.id
              ) < :idleBefore
            order by (
                  select max(se3.finishedAt) from StepExecutionEntity se3
                  where se3.processId = p.id
            ) desc
            """)
    List<String> findStalled(@Param("idleBefore") LocalDateTime idleBefore, Pageable pageable);

}
