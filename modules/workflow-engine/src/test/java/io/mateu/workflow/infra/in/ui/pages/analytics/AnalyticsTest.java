package io.mateu.workflow.infra.in.ui.pages.analytics;

import io.mateu.uidl.data.Card;
import io.mateu.uidl.data.Text;
import io.mateu.workflow.application.services.ProcessAnalyticsService;
import io.mateu.workflow.application.services.ProcessAnalyticsService.DefinitionAnalytics;
import io.mateu.workflow.application.services.ProcessAnalyticsService.DurationStats;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The report must account for every instance it counts: a saga that rolled back is neither
 * completed nor errored, and a process waiting on a human is not finished either. Each of those
 * used to be inside {@code instances} and inside no column of the row that reported it.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsTest {

    @Mock ProcessAnalyticsService analyticsService;

    private DefinitionAnalytics oneOfEachStatus() {
        var byStatus = new EnumMap<ProcessStatus, Long>(ProcessStatus.class);
        for (var status : ProcessStatus.values()) {
            byStatus.put(status, 1L);
        }
        return new DefinitionAnalytics("def-1", "Booking", byStatus.size(), byStatus,
                12.5d, 12.5d, 12.5d, Map.of(), Map.of(),
                new DurationStats(0, null, null), List.of(), null);
    }

    private Analytics page(DefinitionAnalytics... definitions) {
        when(analyticsService.analyzeAll(any())).thenReturn(List.of(definitions));
        var page = new Analytics(analyticsService);
        page.onHydrated(null);
        return page;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> onlyDefinitionRow(Analytics page) {
        var rows = page.definitions.page().content();
        assertThat(rows).hasSize(1);
        return (LinkedHashMap<String, String>) rows.get(0);
    }

    private List<String> kpiTitles(Analytics page) {
        return page.kpis.content().stream()
                .map(card -> ((Text) ((Card) card).title()).text())
                .toList();
    }

    @Test
    void countsEveryStatusItReportsAsAnInstance() {
        var row = onlyDefinitionRow(page(oneOfEachStatus()));

        var perColumn = List.of("completed", "running", "errors", "cancelled",
                        "compensated", "compensationFailed").stream()
                .mapToLong(column -> Long.parseLong(row.get(column)))
                .sum();
        assertThat(perColumn).isEqualTo(Long.parseLong(row.get("instances")));
    }

    @Test
    void showsRolledBackAndPausedInstancesInTheirOwnColumns() {
        var row = onlyDefinitionRow(page(oneOfEachStatus()));

        assertThat(row.get("compensated")).isEqualTo("1");
        assertThat(row.get("compensationFailed")).isEqualTo("1");
        // PENDING + RUNNING + PAUSED: everything still in flight.
        assertThat(row.get("running")).isEqualTo("3");
    }

    @Test
    void headlinesCompensatedProcessesAndFlagsFailedRollbacksWhenThereAreAny() {
        assertThat(kpiTitles(page(oneOfEachStatus())))
                .containsExactly("Processes", "Completed", "Errors", "Cancelled", "Compensated",
                        "Rollback failed");
    }

    @Test
    void hidesTheFailedRollbackCardWhenThereIsNone() {
        var byStatus = new EnumMap<ProcessStatus, Long>(ProcessStatus.class);
        byStatus.put(ProcessStatus.COMPLETED, 4L);
        byStatus.put(ProcessStatus.COMPENSATED, 1L);
        var healthy = new DefinitionAnalytics("def-1", "Booking", 5, byStatus,
                80d, 0d, 0d, Map.of(), Map.of(), new DurationStats(0, null, null), List.of(), null);

        assertThat(kpiTitles(page(healthy)))
                .containsExactly("Processes", "Completed", "Errors", "Cancelled", "Compensated");
    }
}
