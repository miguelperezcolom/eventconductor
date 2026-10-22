package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.LiveProcessStatusView;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.LiveStepStatusView;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

interface LiveOverlayRepository extends Repository<ProcessEntity, String> {

    @Query("""
            select p.workflowDefinitionId as definitionId, p.status as status,
                   count(p) as count, min(p.name) as anyName
            from ProcessEntity p
            where p.finished is null and p.created is not null
              and p.testRun = false
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
              and p.testRun = false
              and not (s.parentExecutionId is null and s.scopeJson is not null)
              and (cast(:from as LocalDateTime) is null or p.created >= :from)
              and (cast(:to as LocalDateTime) is null or p.created <= :to)
            group by s.workflowDefinitionId, s.stepId, s.status
            """)
    List<LiveStepStatusView> liveStepStatus(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);
}
