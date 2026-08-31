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
// Uncap the whole workflow app (this is its root @UI): otherwise every page nested inside it —
// including the full-width definition/process detail — is trapped in this root's 1408px column.
// The dashboard content stays centred at a readable width via HomeStyles' inner rows.
@PageWidth(PageWidthStyle.FULL_WIDTH)
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

    /**
     * Whether this hydration is going to show this page, rather than pass the route to a child.
     *
     * <p>A request carries the route it is for and the part of it consumed so far. When the two
     * agree — including both being empty, which is a visit to the home itself — this component is
     * where the route ends. When they differ there is still routing to do, so whatever this
     * handler builds is discarded before it reaches the browser.
     *
     * <p>Errs towards building: a request with no routing information at all is treated as a
     * visit, because a dashboard that is occasionally computed needlessly is a cost, and one that
     * is occasionally blank is a bug.
     */
    private boolean isTheDestination(HttpRequest httpRequest) {
        if (httpRequest == null || httpRequest.runActionRq() == null) {
            return true;
        }
        var route = httpRequest.runActionRq().route();
        var consumed = httpRequest.runActionRq().consumedRoute();
        if (route == null || route.isBlank()) {
            return true;
        }
        return route.equals(consumed);
    }

    @Override
    public void onHydrated(HttpRequest httpRequest) {
        // Only when this page is what the request is actually for.
        //
        // This app is mounted in the console as a RemoteMenu, so the shell owns the URL and every
        // navigation to any /workflow/* route hydrates this root first, purely to resolve where the
        // route goes. That hydration answers with `component: null` and `data: {}` — it renders
        // nothing — and it used to build the whole dashboard on the way: two GROUP BYs over the
        // process table plus every chart object, then thrown away.
        //
        // Measured on the reference deployment (387 807 processes) from a browser HAR: that
        // routing hop took 777 ms to return 418 bytes of nothing, of which 262 ms was the two
        // aggregates alone. It ran on every hop between Definitions, Processes and Steps.
        if (!isTheDestination(httpRequest)) {
            return;
        }

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
