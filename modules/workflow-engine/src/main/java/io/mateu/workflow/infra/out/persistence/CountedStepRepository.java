package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.CountedStepEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

interface CountedStepRepository extends JpaRepository<CountedStepEntity, String> {

    /**
     * Counted step executions that have since been reset ({@code finished_at} cleared: a process
     * retry or restart, or the engine's own retry of a step). Same shape as
     * {@link CountedProcessRepository#reopened}.
     */
    @Query("""
            select c from CountedStepEntity c, StepExecutionEntity s
            where s.id = c.stepExecutionId and s.finishedAt is null
            """)
    List<CountedStepEntity> reopened(Pageable page);
}
