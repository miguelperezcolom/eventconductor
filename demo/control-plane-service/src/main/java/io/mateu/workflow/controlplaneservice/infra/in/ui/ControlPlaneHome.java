package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.FavIcon;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Logo;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.PageTitle;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.Card;
import io.mateu.uidl.data.Chart;
import io.mateu.uidl.data.ChartAxisScale;
import io.mateu.uidl.data.ChartOptions;
import io.mateu.uidl.data.ChartScales;
import io.mateu.uidl.data.ChartType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.PostHydrationHandler;
import io.mateu.workflow.controlplaneservice.infra.in.ui.adapters.ControlPlaneHomeAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@UI("/_control-plane")
@Title("")
@FavIcon("/images/riu.svg")
@PageTitle("Control Plane")
@Logo("/images/riu.svg")
@Style(HomeStyles.PAGE)
@RequiredArgsConstructor
@Service
public class ControlPlaneHome implements PostHydrationHandler {

    @Menu
    ReleasesMenu controlPlane;

    @Hidden
    final ControlPlaneHomeAdapter adapter;

    io.mateu.uidl.data.HorizontalLayout charts = null;

    io.mateu.uidl.data.HorizontalLayout kpis = null;

    @Stereotype(FieldStereotype.html)
    String message = "<p>Welcome to the control plane.</p>" +
            "<p>Here you will be able to: <ul>" +
            "<li>manage your routes</li>" +
            "<li>create releases</li>" +
            "<li>preview your releases</li>" +
            "<li>review the changes</li>" +
            "<li>manage blue/green deployments and a/b tests</li>" +
            "<li>manage your users</li>" +
            "<li>easily rollback</li>" +
            "</ul></p>";

    @Override
    public void onHydrated(HttpRequest httpRequest) {
        var data = adapter.fetch();

        charts = io.mateu.uidl.data.HorizontalLayout.builder()
                .content(List.of(
                        chartCard("Routes", Chart.builder()
                                .chartType(ChartType.doughnut)
                                .chartData(data.processesByDefinitionChartData())
                                .chartOptions(ChartOptions.builder()
                                        .maintainAspectRatio(false)
                                        .build())
                                .style(HomeStyles.CHART)
                                .build()),
                        chartCard("By status", Chart.builder()
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
                        chartCard("Distribution", Chart.builder()
                                .chartType(ChartType.polarArea)
                                .chartData(data.userTasksChartData())
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
                        kpiCard("Releases", data.processDefinitionsCount()),
                        kpiCard("Pending routes", data.activeProcessesCount()),
                        kpiCard("Changed routes", data.completedProcessesCount()),
                        kpiCard("Routes", data.processesCount()),
                        kpiCard("Languages", data.userTasksCount()),
                        kpiCard("Countries", data.countriesCount())
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
