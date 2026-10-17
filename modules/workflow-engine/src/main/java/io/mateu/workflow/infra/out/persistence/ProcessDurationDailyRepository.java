package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessDurationDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

interface ProcessDurationDailyRepository extends JpaRepository<ProcessDurationDailyEntity, String> {
    @Query("""
            select e from ProcessDurationDailyEntity e
            where (cast(:from as LocalDate) is null or e.createdDay >= :from)
              and (cast(:to as LocalDate) is null or e.createdDay <= :to)
            """)
    List<ProcessDurationDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
