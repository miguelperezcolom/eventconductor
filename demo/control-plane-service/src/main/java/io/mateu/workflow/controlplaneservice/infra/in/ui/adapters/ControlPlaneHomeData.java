package io.mateu.workflow.controlplaneservice.infra.in.ui.adapters;

import io.mateu.uidl.data.ChartData;
import lombok.Builder;

@Builder
public record ControlPlaneHomeData(
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
