package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.ChartData;
import lombok.Builder;

@Builder
public record WorkflowHomeData(
        ChartData processesByDefinitionChartData,
        ChartData processesByStatusChartData,
        ChartData userTasksChartData,
        long processDefinitionsCount,
        long processesCount,
        long activeProcessesCount,
        long completedProcessesCount,
        long formDefinitionsCount,
        long userTasksCount
) {
}
