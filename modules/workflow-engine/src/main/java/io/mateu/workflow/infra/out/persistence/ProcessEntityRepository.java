package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /** Projection for the grouped-count queries above. */
    interface CountByKey {
        String getKey();
        long getCount();
    }

}
