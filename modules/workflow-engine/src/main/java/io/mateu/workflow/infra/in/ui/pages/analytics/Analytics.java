package io.mateu.workflow.infra.in.ui.pages.analytics;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.Card;
import io.mateu.uidl.data.Chart;
import io.mateu.uidl.data.ChartAxisScale;
import io.mateu.uidl.data.ChartData;
import io.mateu.uidl.data.ChartDataset;
import io.mateu.uidl.data.ChartOptions;
import io.mateu.uidl.data.ChartScales;
import io.mateu.uidl.data.ChartType;
import io.mateu.uidl.data.Grid;
import io.mateu.uidl.data.GridColumn;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.PostHydrationHandler;
import io.mateu.workflow.application.services.ProcessAnalyticsService;
import io.mateu.workflow.application.services.ProcessAnalyticsService.DefinitionAnalytics;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in process analytics page: instance counts, rates, throughput and
 * duration percentiles per workflow definition, with the slowest step of each
 * definition flagged as the bottleneck. Fixed window: the last 30 days.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@ReadOnly
@Title("Analytics")
public class Analytics implements PostHydrationHandler {

    static final int WINDOW_DAYS = 30;
    static final int THROUGHPUT_DAYS = 14;

    @Hidden
    final ProcessAnalyticsService analyticsService;

    Text subtitle = null;

    HorizontalLayout kpis = null;

    HorizontalLayout charts = null;

    Text definitionsTitle = null;

    Grid definitions = null;

    Text stepsTitle = null;

    Grid steps = null;

    @Override
    public void onHydrated(HttpRequest httpRequest) {
        var window = ProcessAnalyticsService.TimeWindow.lastDays(WINDOW_DAYS);
        var analytics = analyticsService.analyzeAll(window);

        subtitle = Text.builder()
                .text("Processes created in the last " + WINDOW_DAYS + " days, per workflow definition.")
                .build();

        kpis = buildKpis(analytics);
        charts = buildThroughputChart(analytics);
        definitionsTitle = Text.builder().text("Per definition").style("font-weight: bold; margin-top: 1rem;").build();
        definitions = buildDefinitionsGrid(analytics);
        stepsTitle = Text.builder().text("Per step (bottleneck = slowest average)").style("font-weight: bold; margin-top: 1rem;").build();
        steps = buildStepsGrid(analytics);
    }

    /**
     * The headline counts. Every terminal outcome a process can have needs a card of its own or the
     * numbers do not add up: a rolled-back saga is neither completed nor errored, so before
     * COMPENSATED was here a definition whose instances all compensated showed instances with
     * nothing under them.
     *
     * <p>COMPENSATION_FAILED only earns a card when there is one — it is rare, and it is the
     * outcome an operator must never miss, so it is shown when it happens rather than as a
     * permanent zero next to the four that always matter.
     */
    private HorizontalLayout buildKpis(List<DefinitionAnalytics> analytics) {
        var total = analytics.stream().mapToLong(DefinitionAnalytics::totalInstances).sum();
        var completed = countByStatus(analytics, ProcessStatus.COMPLETED);
        var errored = countByStatus(analytics, ProcessStatus.ERROR);
        var cancelled = countByStatus(analytics, ProcessStatus.CANCELLED);
        var compensated = countByStatus(analytics, ProcessStatus.COMPENSATED);
        var compensationFailed = countByStatus(analytics, ProcessStatus.COMPENSATION_FAILED);
        var cards = new ArrayList<Component>(List.of(
                kpiCard("Processes", "" + total),
                kpiCard("Completed", completed + " (" + pct(completed, total) + ")"),
                kpiCard("Errors", errored + " (" + pct(errored, total) + ")"),
                kpiCard("Cancelled", cancelled + " (" + pct(cancelled, total) + ")"),
                kpiCard("Compensated", compensated + " (" + pct(compensated, total) + ")")));
        if (compensationFailed > 0) {
            cards.add(kpiCard("Rollback failed",
                    compensationFailed + " (" + pct(compensationFailed, total) + ")"));
        }
        return HorizontalLayout.builder()
                .content(List.copyOf(cards))
                .spacing(true)
                .wrap(true)
                .build();
    }

    private HorizontalLayout buildThroughputChart(List<DefinitionAnalytics> analytics) {
        var days = new ArrayList<LocalDate>();
        for (int i = THROUGHPUT_DAYS - 1; i >= 0; i--) {
            days.add(LocalDate.now().minusDays(i));
        }
        var datasets = analytics.stream()
                .filter(definition -> definition.totalInstances() > 0)
                .map(definition -> ChartDataset.builder()
                        .label(definition.workflowDefinitionName())
                        .data(days.stream()
                                .map(day -> definition.createdPerDay().getOrDefault(day, 0L).doubleValue())
                                .toList())
                        .build())
                .toList();
        return HorizontalLayout.builder()
                .content(List.of(Chart.builder()
                        .chartType(ChartType.bar)
                        .chartData(ChartData.builder()
                                .labels(days.stream().map(LocalDate::toString).toList())
                                .datasets(datasets)
                                .build())
                        .chartOptions(ChartOptions.builder()
                                .maintainAspectRatio(false)
                                .scales(ChartScales.builder()
                                        .y(ChartAxisScale.builder().beginAtZero(true).build())
                                        .build())
                                .build())
                        .build()))
                .style("width: 100%; height: 16rem; margin-bottom: 1rem;")
                .build();
    }

    private Grid buildDefinitionsGrid(List<DefinitionAnalytics> analytics) {
        var rows = analytics.stream().map(definition -> {
            var row = new LinkedHashMap<String, String>();
            row.put("definition", definition.workflowDefinitionName());
            row.put("instances", "" + definition.totalInstances());
            row.put("completed", "" + statusCount(definition, ProcessStatus.COMPLETED));
            // In flight: everything that has not reached a terminal state, PAUSED included — a
            // paused process is still an open instance, and left out of here it was an instance
            // this row counted but never showed.
            row.put("running", "" + (statusCount(definition, ProcessStatus.PENDING)
                    + statusCount(definition, ProcessStatus.RUNNING)
                    + statusCount(definition, ProcessStatus.PAUSED)));
            row.put("errors", "" + statusCount(definition, ProcessStatus.ERROR));
            row.put("cancelled", "" + statusCount(definition, ProcessStatus.CANCELLED));
            row.put("compensated", "" + statusCount(definition, ProcessStatus.COMPENSATED));
            row.put("compensationFailed", "" + statusCount(definition, ProcessStatus.COMPENSATION_FAILED));
            row.put("completionRate", definition.completionRatePct() + " %");
            row.put("errorRate", definition.errorRatePct() + " %");
            row.put("avgDuration", format(definition.processDuration().average()));
            row.put("p95Duration", format(definition.processDuration().p95()));
            row.put("bottleneck", definition.bottleneckStepId() != null ? definition.bottleneckStepId() : "");
            return (Object) row;
        }).toList();
        return grid(rows,
                GridColumn.builder().id("definition").label("Definition").build(),
                GridColumn.builder().id("instances").label("Instances").build(),
                GridColumn.builder().id("completed").label("Completed").build(),
                GridColumn.builder().id("running").label("Active").build(),
                GridColumn.builder().id("errors").label("Errors").build(),
                GridColumn.builder().id("cancelled").label("Cancelled").build(),
                GridColumn.builder().id("compensated").label("Compensated").build(),
                GridColumn.builder().id("compensationFailed").label("Rollback failed").build(),
                GridColumn.builder().id("completionRate").label("Completion").build(),
                GridColumn.builder().id("errorRate").label("Error rate").build(),
                GridColumn.builder().id("avgDuration").label("Avg duration").build(),
                GridColumn.builder().id("p95Duration").label("p95 duration").build(),
                GridColumn.builder().id("bottleneck").label("Bottleneck step").build());
    }

    private Grid buildStepsGrid(List<DefinitionAnalytics> analytics) {
        var rows = analytics.stream()
                .flatMap(definition -> definition.steps().stream().map(step -> {
                    var row = new LinkedHashMap<String, String>();
                    row.put("definition", definition.workflowDefinitionName());
                    row.put("step", step.stepId() + (step.bottleneck() ? " ⚠" : ""));
                    row.put("executions", "" + step.executions());
                    row.put("completed", "" + step.completed());
                    row.put("failed", "" + step.failed());
                    row.put("active", "" + step.active());
                    row.put("avgDuration", format(step.duration().average()));
                    row.put("p95Duration", format(step.duration().p95()));
                    return (Object) row;
                }))
                .toList();
        return grid(rows,
                GridColumn.builder().id("definition").label("Definition").build(),
                GridColumn.builder().id("step").label("Step").build(),
                GridColumn.builder().id("executions").label("Executions").build(),
                GridColumn.builder().id("completed").label("Completed").build(),
                GridColumn.builder().id("failed").label("Failed").build(),
                GridColumn.builder().id("active").label("Active").build(),
                GridColumn.builder().id("avgDuration").label("Avg duration").build(),
                GridColumn.builder().id("p95Duration").label("p95 duration").build());
    }

    private Grid grid(List<Object> rows, GridColumn... columns) {
        return Grid.builder()
                .content(List.of(columns))
                .page(new Page<>("", rows.size(), 0, rows.size(), rows))
                .build();
    }

    private Card kpiCard(String title, String value) {
        return Card.builder()
                .title(Text.builder().text(title).build())
                .content(Text.builder().text(value).style("text-align: center;").build())
                .style("flex-grow: 1;width: 12rem;")
                .build();
    }

    private static long countByStatus(List<DefinitionAnalytics> analytics, ProcessStatus status) {
        return analytics.stream().mapToLong(definition -> statusCount(definition, status)).sum();
    }

    private static long statusCount(DefinitionAnalytics definition, ProcessStatus status) {
        return definition.instancesByStatus().getOrDefault(status, 0L);
    }

    private static String pct(long part, long total) {
        return total == 0 ? "0 %" : (Math.round(1000d * part / total) / 10d) + " %";
    }

    static String format(Duration duration) {
        if (duration == null) {
            return "-";
        }
        if (duration.toMillis() < 1000) {
            return duration.toMillis() + " ms";
        }
        if (duration.toSeconds() < 60) {
            return (Math.round(duration.toMillis() / 100d) / 10d) + " s";
        }
        if (duration.toMinutes() < 60) {
            return duration.toMinutes() + " m " + (duration.toSeconds() % 60) + " s";
        }
        return duration.toHours() + " h " + (duration.toMinutes() % 60) + " m";
    }
}
