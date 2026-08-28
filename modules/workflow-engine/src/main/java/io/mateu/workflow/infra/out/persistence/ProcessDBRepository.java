package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.AnalyticsAggregates;
import io.mateu.workflow.application.out.ProcessAnalyticsRow;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.paging.ServedPage;
import io.mateu.workflow.application.out.ProcessSummary;
import io.mateu.workflow.application.out.ProcessSummaryPage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class ProcessDBRepository implements ProcessRepository {

    final ProcessEntityRepository processEntityRepository;
    final OutboxMessageEntityRepository outboxMessageEntityRepository;
    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;
    final io.mateu.workflow.infra.out.async.OutboxSignal outboxSignal;
    final io.mateu.workflow.application.services.ProcessStatusAnnouncer processStatusAnnouncer;

    @Override
    public Optional<Process> findById(String id) {
        return processEntityRepository.findById(id)
                .map(this::map);
    }

    private Process map(ProcessEntity entity) {
        return new Process(
                entity.getId(),
                entity.getName(),
                entity.getWorkflowDefinitionId(),
                entity.getWorkflowDefinitionVersion(),
                entity.getWorkflowDefinitionJson(),
                entity.getBusinessKey(),
                listFromJson(entity.getVariables(), Variable.class),
                ProcessStatus.valueOf(entity.getStatus()),
                entity.getCompletionPercentage(),
                entity.getCreated(),
                entity.getStarted(),
                entity.getFinished(),
                entity.getPausedAt(),
                entity.getParentStepExecutionId(),
                entity.getVersion()
        );
    }

    @Override
    public String save(Process process) {
        // Read-model event, emitted at the one point every status transition funnels through: if the
        // read model is on and this save changes the status, ride a ProcessStatusChanged through the
        // outbox alongside the other domain events. Off → no prior-status read, no event.
        if (processStatusAnnouncer.isEnabled()) {
            var previousStatus = processEntityRepository.findById(process.getId())
                    .map(entity -> ProcessStatus.valueOf(entity.getStatus()))
                    .orElse(null);
            processStatusAnnouncer.announceIfChanged(process, previousStatus);
        }
        // Normalize empty businessKey to null so the unique constraint does not
        // reject multiple processes that have no business key.
        var businessKey = (process.getBusinessKey() == null || process.getBusinessKey().isBlank())
                ? null : process.getBusinessKey();
        processEntityRepository.save(new ProcessEntity(
                process.getId(),
                businessKey,
                process.getName(),
                toJson(process.getVariables()),
                process.getStatus().name(),
                process.getCompletionPercentage(),
                "log",
                process.getWorkflowDefinitionId(),
                process.getWorkflowDefinitionVersion(),
                process.getWorkflowDefinitionJson(),
                process.getCreated(),
                process.getStarted(),
                process.getFinished(),
                process.getPausedAt(),
                process.getParentStepExecutionId(),
                process.getVersion()
        ));
        // Captured here, at the one moment the event and the context that produced it are both
        // in hand: the relay publishes this row later, from a thread that has neither.
        var traceParent = workflowTracing.currentTraceParent();
        var outbox = process.popEvents().stream()
                .map(event -> new OutboxMessageEntity(event, traceParent)).toList();
        outboxMessageEntityRepository.saveAll(outbox);
        if (!outbox.isEmpty()) {
            // Wake this pod's relay once the transaction commits, rather than leaving the row to
            // be found on the next poll — which is latency added to every step.
            outboxSignal.raise();
        }
        return process.getId();
    }

    @Override
    public List<Process> findAll() {
        return processEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        processEntityRepository.deleteAllById(selectedIds);
    }

    @Override
    public Optional<Process> findByBusinessKey(String businessKey) {
        return processEntityRepository.findByBusinessKey(businessKey)
                .map(this::map);
    }

    @Override
    public long countByStatus(ProcessStatus status) {
        return processEntityRepository.countByStatus(status.name());
    }

    /**
     * Six {@code GROUP BY}s instead of every process in the window.
     *
     * <p>The report is about fifty rows on screen. Folding it in Java meant materialising every
     * process that fed it — 37 651 on the deployment this was measured against, and the step half
     * was ten times that. The database does the same reduction and returns one row per definition
     * and status.
     */
    @Override
    public AnalyticsAggregates.ProcessAggregates aggregateProcesses(LocalDateTime from, LocalDateTime to) {
        var statusCounts = processEntityRepository.aggregateStatusCounts(from, to).stream()
                .map(v -> new AnalyticsAggregates.DefinitionStatusCount(
                        v.getDefinitionId(), ProcessStatus.valueOf(v.getStatus()), v.getCount(), v.getAnyName()))
                .toList();
        return new AnalyticsAggregates.ProcessAggregates(
                statusCounts,
                perDay(processEntityRepository.aggregateCreatedPerDay(from, to)),
                perDay(processEntityRepository.aggregateFinishedPerDay(from, to)),
                processEntityRepository.aggregateDurations(from, to).stream()
                        .map(v -> new AnalyticsAggregates.DefinitionDuration(v.getDefinitionId(),
                                new AnalyticsAggregates.DurationAggregate(
                                        v.getSamples(), v.getTotalNanos(), v.getP95Nanos())))
                        .toList());
    }

    private static List<AnalyticsAggregates.DefinitionDayCount> perDay(
            List<ProcessEntityRepository.DayCountView> views) {
        return views.stream()
                .map(v -> new AnalyticsAggregates.DefinitionDayCount(
                        v.getDefinitionId(), v.getDay(), v.getCount()))
                .toList();
    }

    /**
     * The window goes into the query, and the query selects seven columns instead of the row. The
     * default would have loaded every process in the window as a full aggregate — definition JSON
     * included — to compute counts and averages over it.
     */
    @Override
    public List<ProcessAnalyticsRow> findAnalyticsRows(LocalDateTime createdFrom, LocalDateTime createdTo) {
        return processEntityRepository.findAnalyticsRows(createdFrom, createdTo).stream()
                .map(view -> new ProcessAnalyticsRow(
                        view.getId(),
                        view.getName(),
                        view.getWorkflowDefinitionId(),
                        ProcessStatus.valueOf(view.getStatus()),
                        view.getCreated(),
                        view.getStarted(),
                        view.getFinished()))
                .toList();
    }

    /**
     * Pushed all the way down to SQL, unlike the in-memory default: the write-side process row
     * carries the workflow definition JSON, so loading the table to show ten rows moves hundreds of
     * megabytes per keystroke once a deployment has been running for a while.
     */
    @Override
    public ProcessSummaryPage searchSummaries(
            io.mateu.workflow.application.out.ProcessListingFilter filter, int page, int size) {
        var searchText = filter.normalisedSearchText();
        var pattern = searchText == null ? null : "%" + searchText.toLowerCase() + "%";
        // The status travels as its name: the column is a string, and passing the enum would leave
        // the comparison to however the provider chooses to bind it.
        var status = filter.status() == null ? null : filter.status().name();
        // Counted first, because which page can be served depends on how many there are — see
        // ServedPage. Two queries either way: a Spring Data Page would have run this same count.
        var total = processEntityRepository.countSummaries(filter.onlyErrors(), pattern,
                filter.workflowDefinitionId(), status, filter.createdFrom(), filter.createdTo());
        var served = ServedPage.of(page, size, total);
        var content = processEntityRepository
                .searchSummaries(filter.onlyErrors(), pattern,
                        filter.workflowDefinitionId(), status, filter.createdFrom(), filter.createdTo(),
                        PageRequest.of(served.number(), served.size()))
                .stream()
                .map(view -> new ProcessSummary(
                        view.getId(),
                        view.getName(),
                        ProcessStatus.valueOf(view.getStatus()),
                        view.getCompletionPercentage(),
                        view.getCreated(),
                        view.getStarted(),
                        view.getFinished()))
                .toList();
        return new ProcessSummaryPage(content, total, served.number(), served.size());
    }

    @Override
    public List<String> findStalled(LocalDateTime idleBefore, int limit) {
        return processEntityRepository.findStalled(idleBefore,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

}
