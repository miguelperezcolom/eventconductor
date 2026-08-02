package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.data.*;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.PostHydrationHandler;
import io.mateu.workflow.infra.in.ui.adapters.WorkflowHomeAdapter;
import io.mateu.workflow.infra.in.ui.pages.process.Processes;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.List;

@UI("/_workflow")
//@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Workflow")
@Logo("/images/riu.svg")
@Title("")
@Style(HomeStyles.PAGE)
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
public class WorkflowHome implements PostHydrationHandler {

    @Hidden
    final WorkflowHomeAdapter adapter;

    io.mateu.uidl.data.HorizontalLayout charts = null;

    io.mateu.uidl.data.HorizontalLayout kpis = null;

    @Stereotype(FieldStereotype.html)
    String message = "<p>Welcome to the event driven orchestrator.</p>" +
            "<p>Here you will be able to create workflow definitions and processes.</p>";

    @Menu
    WorkflowMenu workflow;

    @Override
    public void onHydrated(HttpRequest httpRequest) {
        var data = adapter.fetch();

        charts = io.mateu.uidl.data.HorizontalLayout.builder()
                .content(List.of(
                        chartCard("Processes by status", Chart.builder()
                                .chartType(ChartType.bar)
                                .chartData(data.processesByStatusChartData())
                                .chartOptions(ChartOptions.builder()
                                        .maintainAspectRatio(false)
                                        .scales(ChartScales.builder()
                                                .y(ChartAxisScale.builder()
                                                        .beginAtZero(true)
                                                        .build())
                                                .build())
                                        .build())
                                .style(HomeStyles.CHART)
                                .build()),
                        chartCard("Processes by definition", Chart.builder()
                                .chartType(ChartType.doughnut)
                                .chartData(data.processesByDefinitionChartData())
                                .chartOptions(ChartOptions.builder()
                                        .maintainAspectRatio(false)
                                        .build())
                                .style(HomeStyles.CHART)
                                .build())
                ))
                .style(HomeStyles.CHART_ROW)
                .build();

        kpis = io.mateu.uidl.data.HorizontalLayout.builder()
                .content(List.of(
                        kpiCard("Process Definitions", data.processDefinitionsCount()),
                        kpiCard("Running Processes", data.activeProcessesCount()),
                        kpiCard("Completed Processes", data.completedProcessesCount()),
                        kpiCard("Form Definitions", data.formDefinitionsCount()),
                        kpiCard("User Tasks", data.userTasksCount())
                ))
                .style(HomeStyles.KPI_ROW)
                .build();
    }

    /** A KPI card: a small uppercase label above a large, bold value. */
    private static Card kpiCard(String label, Object value) {
        return Card.builder()
                .title(Text.builder().text(label).style(HomeStyles.KPI_LABEL).build())
                .content(Text.builder().text(String.valueOf(value)).style(HomeStyles.KPI_VALUE).build())
                .style(HomeStyles.KPI_CARD)
                .build();
    }

    /** A chart card: an uppercase caption above a bounded chart. */
    private static Card chartCard(String caption, Chart chart) {
        return Card.builder()
                .title(Text.builder().text(caption).style(HomeStyles.CHART_TITLE).build())
                .content(chart)
                .style(HomeStyles.CHART_CARD)
                .build();
    }
}
