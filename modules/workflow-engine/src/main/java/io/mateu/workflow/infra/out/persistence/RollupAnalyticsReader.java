package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.AnalyticsAggregates;
import io.mateu.workflow.application.out.RollupAnalyticsPort;
import io.mateu.workflow.application.out.analytics.DurationHistogram;
import io.mateu.workflow.application.out.analytics.RollupModel;
import io.mateu.workflow.application.out.analytics.RollupReducer;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.LiveOverlayRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessCreatedDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessDurationDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessFinishedDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessStatusDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.StepDurationDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.StepStatusDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Answers the analytics aggregates from the rollup tables plus the live overlay — the read side of
 * the read model. Loads the rollup rows a window covers, reads what is still in flight straight off
 * the raw table, and hands both to {@link RollupReducer}, which sums them into the same
 * {@link AnalyticsAggregates} the raw {@code GROUP BY} would have produced.
 *
 * <p>The window is applied at day granularity — a rolling 30-day window includes the whole of the
 * boundary day rather than the exact hour thirty days ago. That is the natural resolution of a
 * store bucketed by day, and for a report whose smallest control is "last N days" it is the honest
 * one. The live overlay, read off raw rows, keeps the exact timestamp window.
 */
@Component
@ConditionalOnProperty(name = "workflow.analytics.rollup", havingValue = "true")
@RequiredArgsConstructor
public class RollupAnalyticsReader implements RollupAnalyticsPort {

    final ProcessCreatedDailyRepository createdDaily;
    final ProcessFinishedDailyRepository finishedDaily;
    final ProcessStatusDailyRepository statusDaily;
    final ProcessDurationDailyRepository durationDaily;
    final StepStatusDailyRepository stepStatusDaily;
    final StepDurationDailyRepository stepDurationDaily;
    final LiveOverlayRepository liveOverlay;

    @Override
    @Transactional(readOnly = true)
    public AnalyticsAggregates.ProcessAggregates aggregateProcesses(LocalDateTime from, LocalDateTime to) {
        var fromDay = day(from);
        var toDay = day(to);

        var status = statusDaily.inWindow(fromDay, toDay).stream()
                .map(e -> new RollupModel.ProcessStatusDaily(e.getWorkflowDefinitionId(),
                        e.getCreatedDay(), ProcessStatus.valueOf(e.getStatus()), e.getCnt(), e.getAnyName()))
                .toList();
        var live = liveOverlay.liveProcessStatus(from, to).stream()
                .map(v -> new RollupModel.LiveProcessStatus(v.getDefinitionId(),
                        ProcessStatus.valueOf(v.getStatus()), v.getCount(), v.getAnyName()))
                .toList();
        var created = createdDaily.inWindow(fromDay, toDay).stream()
                .map(e -> new RollupModel.ProcessCreatedDaily(e.getWorkflowDefinitionId(), e.getDay(), e.getCnt()))
                .toList();
        var finished = finishedDaily.inWindow(fromDay, toDay).stream()
                .map(e -> new RollupModel.ProcessFinishedDaily(e.getWorkflowDefinitionId(),
                        e.getCreatedDay(), e.getFinishedDay(), e.getCnt()))
                .toList();
        var durations = durationDaily.inWindow(fromDay, toDay).stream()
                .map(e -> new RollupModel.ProcessDurationDaily(e.getWorkflowDefinitionId(),
                        e.getCreatedDay(), e.getSamples(), e.getTotalNanos(),
                        DurationHistogram.parse(e.getHistogram())))
                .toList();

        return RollupReducer.reduceProcesses(status, live, created, finished, durations);
    }

    @Override
    @Transactional(readOnly = true)
    public AnalyticsAggregates.StepAggregates aggregateSteps(LocalDateTime from, LocalDateTime to) {
        var fromDay = day(from);
        var toDay = day(to);

        var status = stepStatusDaily.inWindow(fromDay, toDay).stream()
                .map(e -> new RollupModel.StepStatusDaily(e.getWorkflowDefinitionId(), e.getStepId(),
                        e.getCreatedDay(), StepExecutionStatus.valueOf(e.getStatus()), e.getCnt(),
                        e.getFirstOrder()))
                .toList();
        var live = liveOverlay.liveStepStatus(from, to).stream()
                .map(v -> new RollupModel.LiveStepStatus(v.getDefinitionId(), v.getStepId(),
                        StepExecutionStatus.valueOf(v.getStatus()), v.getCount(), v.getFirstOrder()))
                .toList();
        var durations = stepDurationDaily.inWindow(fromDay, toDay).stream()
                .map(e -> new RollupModel.StepDurationDaily(e.getWorkflowDefinitionId(), e.getStepId(),
                        e.getCreatedDay(), e.getSamples(), e.getTotalNanos(),
                        DurationHistogram.parse(e.getHistogram())))
                .toList();

        return RollupReducer.reduceSteps(status, live, durations);
    }

    private static LocalDate day(LocalDateTime moment) {
        return moment == null ? null : moment.toLocalDate();
    }
}
