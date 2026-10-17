package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessCreatedDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

interface ProcessCreatedDailyRepository extends JpaRepository<ProcessCreatedDailyEntity, String> {
    @Query("""
            select e from ProcessCreatedDailyEntity e
            where (cast(:from as LocalDate) is null or e.day >= :from)
              and (cast(:to as LocalDate) is null or e.day <= :to)
            """)
    List<ProcessCreatedDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
