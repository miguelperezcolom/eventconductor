package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.CountedProcessEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

interface CountedProcessRepository extends JpaRepository<CountedProcessEntity, String> {

    /**
     * Counted processes that have since been reopened (retried or restarted: {@code finished}
     * cleared). Driven from the in-flight rows — the same set the live overlay counts — and joined to
     * the ledger by primary key, so it costs what the overlay costs, not a scan of history.
     */
    @Query("""
            select c from CountedProcessEntity c, ProcessEntity p
            where p.id = c.processId and p.finished is null
            """)
    List<CountedProcessEntity> reopened(Pageable page);
}
