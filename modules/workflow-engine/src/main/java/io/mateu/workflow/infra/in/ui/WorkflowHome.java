package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.StyleConstants;
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
import org.springframework.stereotype.Service;

import java.util.List;

@UI("/_workflow")
//@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Workflow")
@Logo("/images/riu.svg")
@Title("")
@Style(StyleConstants.CONTAINER)
@RequiredArgsConstructor
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
                .content(    List.of(
                        Chart.builder()
                                .chartType(ChartType.doughnut)
                                .chartData(data.processesByDefinitionChartData())
                                .chartOptions(ChartOptions.builder()
                                        .maintainAspectRatio(false)
                                        .build())
                                .build(),
                        Chart.builder()
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
                                .build()
                ))
                .style("width: 100%;justify-content: space-around; margin-bottom: 2rem;align-items: center;")
                .build();

        kpis = io.mateu.uidl.data.HorizontalLayout.builder()
                .content(List.of(
                        Card.builder()
                                .title(Text.builder().text("Process Definitions").build())
                                .content(Text.builder().text("" + data.processDefinitionsCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("Running Processes").build())
                                .content(Text.builder().text("" + data.activeProcessesCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("Completed Processes").build())
                                .content(Text.builder().text("" + data.completedProcessesCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("Form Definitions").build())
                                .content(Text.builder().text("" + data.formDefinitionsCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("User Tasks").build())
                                .content(Text.builder().text("" + data.userTasksCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build()
                ))
                .spacing(true)
                .build();
    }
}
