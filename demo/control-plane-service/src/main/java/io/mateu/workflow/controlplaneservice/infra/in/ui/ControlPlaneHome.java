package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.StyleConstants;
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
@Style(StyleConstants.CONTAINER)
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
                                .build(),
                        Chart.builder()
                                .chartType(ChartType.polarArea)
                                .chartData(data.userTasksChartData())
                                .chartOptions(ChartOptions.builder()
                                        .maintainAspectRatio(false)
                                        .build())
                                .build()
                ))
                .style("width: 100%;justify-content: space-around; margin-bottom: 2rem;align-items: center;")
                .build();

        kpis = io.mateu.uidl.data.HorizontalLayout.builder()
                .content(List.of(
                        Card.builder()
                                .title(Text.builder().text("Releases").build())
                                .content(Text.builder().text("" + data.processDefinitionsCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("Pending routes").build())
                                .content(Text.builder().text("" + data.activeProcessesCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("Changed routes").build())
                                .content(Text.builder().text("" + data.completedProcessesCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("Routes").build())
                                .content(Text.builder().text("" + data.processesCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("Languages").build())
                                .content(Text.builder().text("" + data.userTasksCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build(),
                        Card.builder()
                                .title(Text.builder().text("Countries").build())
                                .content(Text.builder().text("" + data.countriesCount()).style("text-align: center;").build())
                                .style("flex-grow: 1;width: 12rem;")
                                .build()
                ))
                .spacing(true)
                .build();
    }

}
