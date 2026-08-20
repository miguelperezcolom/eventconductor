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
            order by p.created desc nulls last
            """)
    List<ProcessSummaryView> searchSummaries(@Param("onlyErrors") boolean onlyErrors,
                                             @Param("pattern") String pattern,
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
            """)
    long countSummaries(@Param("onlyErrors") boolean onlyErrors, @Param("pattern") String pattern);

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
              and (:createdFrom is null or p.created >= :createdFrom)
              and (:createdTo is null or p.created <= :createdTo)
            """)
    List<ProcessAnalyticsView> findAnalyticsRows(@Param("createdFrom") LocalDateTime createdFrom,
                                                 @Param("createdTo") LocalDateTime createdTo);

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

}
