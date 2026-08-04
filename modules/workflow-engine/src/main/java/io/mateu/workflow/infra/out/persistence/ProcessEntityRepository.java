package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
