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

import java.util.HashMap;
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

        Map<String, Double> processesByDefinition = processEntityRepository.findAll().stream()
                .collect(LinkedHashMap::new, (m, p) ->
                        m.put(processDefinitionName(p.getWorkflowDefinitionId()), m.getOrDefault(processDefinitionName(p.getWorkflowDefinitionId()), 0D) + 1L),
                        HashMap::putAll);
        Map<String, Double> processesByStatus = processEntityRepository.findAll().stream()
                .collect(LinkedHashMap::new, (m, p) ->
                        m.put(p.getStatus(), m.getOrDefault(p.getStatus(), 0D) + 1L), HashMap::putAll);
        Map<String, Double> userTasksByStatus = processEntityRepository.findAll().stream()
                .collect(LinkedHashMap::new, (m, p) ->
                        m.put(p.getStatus(), m.getOrDefault(p.getStatus(), 0D) + 1L), HashMap::putAll);

        return WorkflowHomeData.builder()
                .processDefinitionsCount(workflowDefinitionEntityRepository.count())
                .activeProcessesCount(processEntityRepository.findAll().stream()
                        .filter(process ->
                                ProcessStatus.PENDING.name().equals(process.getStatus())
                                || ProcessStatus.RUNNING.name().equals(process.getStatus()))
                        .count())
                .completedProcessesCount(processEntityRepository.findAll().stream()
                        .filter(process ->
                                ProcessStatus.COMPLETED.name().equals(process.getStatus())
                                        || ProcessStatus.ERROR.name().equals(process.getStatus())
                                        || ProcessStatus.CANCELLED.name().equals(process.getStatus())
                                        || ProcessStatus.COMPENSATED.name().equals(process.getStatus()))
                        .count())
                .processesCount(processEntityRepository.count())
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
