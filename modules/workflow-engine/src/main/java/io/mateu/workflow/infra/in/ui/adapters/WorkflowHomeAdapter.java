package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.ChartData;
import io.mateu.uidl.data.ChartDataset;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.out.persistence.ProcessEntityRepository;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionEntity;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@RequiredArgsConstructor
public class WorkflowHomeAdapter {

    final ProcessEntityRepository processEntityRepository;
    final WorkflowDefinitionEntityRepository workflowDefinitionEntityRepository;

    public WorkflowHomeData fetch() {

        // Two GROUP BY count queries (a handful of rows each) instead of loading every process row
        // five times: opening the workflow home used to run five full scans of the process table.
        var byStatus = processEntityRepository.countGroupedByStatus();
        var byDefinition = processEntityRepository.countGroupedByDefinition();

        Map<String, Double> processesByStatus = new LinkedHashMap<>();
        byStatus.forEach(c -> processesByStatus.put(c.getKey(), (double) c.getCount()));
        Map<String, Double> processesByDefinition = new LinkedHashMap<>();
        byDefinition.forEach(c -> processesByDefinition.merge(
                processDefinitionName(c.getKey()), (double) c.getCount(), Double::sum));

        long activeProcesses = byStatus.stream()
                .filter(c -> ProcessStatus.PENDING.name().equals(c.getKey())
                        || ProcessStatus.RUNNING.name().equals(c.getKey()))
                .mapToLong(ProcessEntityRepository.CountByKey::getCount).sum();
        long completedProcesses = byStatus.stream()
                .filter(c -> ProcessStatus.COMPLETED.name().equals(c.getKey())
                        || ProcessStatus.ERROR.name().equals(c.getKey())
                        || ProcessStatus.CANCELLED.name().equals(c.getKey())
                        || ProcessStatus.COMPENSATED.name().equals(c.getKey()))
                .mapToLong(ProcessEntityRepository.CountByKey::getCount).sum();
        long totalProcesses = byStatus.stream()
                .mapToLong(ProcessEntityRepository.CountByKey::getCount).sum();

        return WorkflowHomeData.builder()
                .processDefinitionsCount(workflowDefinitionEntityRepository.count())
                .activeProcessesCount(activeProcesses)
                .completedProcessesCount(completedProcesses)
                .processesCount(totalProcesses)
                .processesByDefinitionChartData(ChartData.builder()
                        .labels(processesByDefinition.keySet().stream().toList())
                        .datasets(List.of(ChartDataset.builder()
                                .label("label 1")
                                .data(processesByDefinition.values().stream().toList())
                                .build()))
                        .build())
                .processesByStatusChartData(ChartData.builder()
                        .labels(processesByStatus.keySet().stream().toList())
                        .datasets(List.of(ChartDataset.builder()
                                .label("Processes")
                                .data(processesByStatus.values().stream().toList())
                                .build()))
                        .build())
                .userTasksChartData(ChartData.builder()
                        .labels(processesByDefinition.keySet().stream().toList())
                        .datasets(List.of(ChartDataset.builder()
                                .label("label 1")
                                .data(processesByDefinition.values().stream().toList())
                                .build()))
                        .build())
                .build();
    }

    @Cacheable(value = "processDefinitionName", key = "#workflowDefinitionId")
    public String processDefinitionName(String workflowDefinitionId) {
        return workflowDefinitionEntityRepository
                .findById(workflowDefinitionId)
                .map(WorkflowDefinitionEntity::getName)
                .orElse("unknown");
    }

}
