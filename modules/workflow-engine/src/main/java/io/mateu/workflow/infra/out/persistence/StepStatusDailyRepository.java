package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.StepStatusDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

interface StepStatusDailyRepository extends JpaRepository<StepStatusDailyEntity, String> {
    @Query("""
            select e from StepStatusDailyEntity e
            where (cast(:from as LocalDate) is null or e.createdDay >= :from)
              and (cast(:to as LocalDate) is null or e.createdDay <= :to)
            """)
    List<StepStatusDailyEntity> inWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
