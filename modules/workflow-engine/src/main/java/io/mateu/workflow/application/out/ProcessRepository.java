package io.mateu.workflow.application.out;

import io.mateu.workflow.paging.ServedPage;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

public interface ProcessRepository extends CrudStore<Process> {

    Optional<Process> findByBusinessKey(String businessKey);

    default long countByStatus(ProcessStatus status) {
        return findAll().stream().filter(process -> status.equals(process.getStatus())).count();
    }

    /**
     * One page of the process listing, newest first. Filtering, ordering and paging all belong to
     * the store: a listing that pages in memory has to load every process first, and the write-side
     * process row carries the workflow definition JSON with it.
     *
     * <p>This default implementation is the in-memory one — the whole store is already in the heap
     * there, so there is nothing to push down. The JPA store overrides it with a real query.
     *
     * @param searchText matched against name and business key, case-insensitively; null or blank
     *                   matches everything
     * @param onlyErrors keep only processes in {@link ProcessStatus#ERROR}
     * @param page       zero-based page number
     * @param size       rows per page
     */
    /**
     * Every process created within the window, as the few fields analytics reads. Analytics is the
     * one screen that legitimately looks at all of them at once, which is exactly why it must not
     * do it by loading aggregates: on a deployment with tens of thousands of processes that was
     * hundreds of megabytes of workflow definition JSON pulled out of the database to compute a
     * handful of counts, and it took the pod down with it.
     *
     * <p>Null bounds are unbounded; a process with no creation date is outside every window, which
     * is what {@code TimeWindow.contains} says of it.
     */
    default List<ProcessAnalyticsRow> findAnalyticsRows(LocalDateTime createdFrom, LocalDateTime createdTo) {
        return findAll().stream()
                .filter(process -> withinWindow(process.getCreated(), createdFrom, createdTo))
                .map(ProcessAnalyticsRow::from)
                .toList();
    }

    /**
     * The analytics report's process half, already reduced — counts by status, counts per day, and
     * the duration distribution — for the processes created inside the window.
     *
     * <p>This default is the in-memory one, and it folds the rows exactly as the service used to,
     * so memory mode keeps the numbers it always had. Memory mode is small by construction: that is
     * the whole reason it exists. The JPA store overrides it with six {@code GROUP BY}s, because
     * there the alternative was loading every process in the window to count them.
     */
    default AnalyticsAggregates.ProcessAggregates aggregateProcesses(LocalDateTime from, LocalDateTime to) {
        var rows = findAnalyticsRows(from, to);
        return new AnalyticsAggregates.ProcessAggregates(
                statusCounts(rows), perDay(rows, ProcessAnalyticsRow::created),
                perDay(rows, ProcessAnalyticsRow::finished), durations(rows));
    }

    private static List<AnalyticsAggregates.DefinitionStatusCount> statusCounts(List<ProcessAnalyticsRow> rows) {
        var counts = new LinkedHashMap<String, Long>();
        var names = new LinkedHashMap<String, String>();
        var statuses = new LinkedHashMap<String, ProcessStatus>();
        rows.forEach(row -> {
            var key = row.workflowDefinitionId() + "\u0000" + row.status();
            counts.merge(key, 1L, Long::sum);
            statuses.putIfAbsent(key, row.status());
            if (row.name() != null) {
                names.putIfAbsent(row.workflowDefinitionId(), row.name());
            }
        });
        return counts.entrySet().stream()
                .map(e -> {
                    var definitionId = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
                    return new AnalyticsAggregates.DefinitionStatusCount(definitionId,
                            statuses.get(e.getKey()), e.getValue(), names.get(definitionId));
                })
                .toList();
    }

    private static List<AnalyticsAggregates.DefinitionDayCount> perDay(
            List<ProcessAnalyticsRow> rows, java.util.function.Function<ProcessAnalyticsRow, LocalDateTime> timestamp) {
        var counts = new LinkedHashMap<String, Long>();
        var days = new LinkedHashMap<String, java.time.LocalDate>();
        rows.forEach(row -> {
            var moment = timestamp.apply(row);
            if (moment == null) {
                return;
            }
            var key = row.workflowDefinitionId() + "\u0000" + moment.toLocalDate();
            counts.merge(key, 1L, Long::sum);
            days.putIfAbsent(key, moment.toLocalDate());
        });
        return counts.entrySet().stream()
                .map(e -> new AnalyticsAggregates.DefinitionDayCount(
                        e.getKey().substring(0, e.getKey().indexOf('\u0000')),
                        days.get(e.getKey()), e.getValue()))
                .toList();
    }

    private static List<AnalyticsAggregates.DefinitionDuration> durations(List<ProcessAnalyticsRow> rows) {
        var byDefinition = new LinkedHashMap<String, List<Long>>();
        rows.forEach(row -> {
            var nanos = durationNanosOf(row);
            if (nanos != null) {
                byDefinition.computeIfAbsent(row.workflowDefinitionId(), id -> new ArrayList<>()).add(nanos);
            }
        });
        return byDefinition.entrySet().stream()
                .map(e -> new AnalyticsAggregates.DefinitionDuration(
                        e.getKey(), AnalyticsMath.aggregate(e.getValue())))
                .toList();
    }

    /**
     * A process's measured duration in nanoseconds, from whichever of started/created it has to
     * whenever it finished. Null when it has not finished, which is what excludes it.
     */
    static Long durationNanosOf(ProcessAnalyticsRow row) {
        var start = row.started() != null ? row.started() : row.created();
        if (start == null || row.finished() == null) {
            return null;
        }
        return java.time.Duration.between(start, row.finished()).toNanos();
    }

    /** The window test {@code TimeWindow.contains} applies, shared by both stores. */
    static boolean withinWindow(LocalDateTime moment, LocalDateTime from, LocalDateTime to) {
        return moment != null
                && (from == null || !moment.isBefore(from))
                && (to == null || !moment.isAfter(to));
    }

    default ProcessSummaryPage searchSummaries(String searchText, boolean onlyErrors, int page, int size) {
        var needle = searchText == null ? "" : searchText.toLowerCase();
        var matching = findAll().stream()
                .filter(process -> !onlyErrors || ProcessStatus.ERROR.equals(process.getStatus()))
                .filter(process -> needle.isEmpty()
                        || process.searchableText().toLowerCase().contains(needle))
                // nullsFirst before the reverse, so a process with no creation date sorts last
                .sorted(Comparator.comparing(Process::getCreated,
                        Comparator.nullsFirst(Comparator.<java.time.LocalDateTime>naturalOrder())).reversed())
                .toList();
        var served = ServedPage.of(page, size, matching.size());
        var content = matching.stream()
                .skip(served.offset())
                .limit(served.size())
                .map(ProcessSummary::from)
                .toList();
        return new ProcessSummaryPage(content, matching.size(), served.number(), served.size());
    }


    /**
     * Processes that are RUNNING, have no step execution left to run, and have not moved since
     * {@code idleBefore}.
     *
     * <p>A process in that state is stopped for good, and nothing else in the engine notices. The
     * step-level watch cannot see it — that one counts <em>live</em> steps waiting on a worker, and
     * here there are none: every step is either finished or was never eligible. Nor can a timeout,
     * because a step that never started has no deadline. It is the one shape of stuck that leaves
     * no clock running anywhere.
     *
     * <p>What puts a process here is a branch none of whose guards was true — the value they
     * compare against turned out to be one nobody wrote a branch for. That is a gap in the
     * definition rather than a fault in the engine, which is why this reports rather than failing
     * the process: the repair is a definition change, and cancelling it here would destroy the
     * evidence of what it was waiting to match.
     *
     * <p>Last movement is the newest {@code finishedAt} among the process's step executions, since
     * a process row carries no timestamp of its own for it.
     *
     * @param limit ceiling on the ids returned. A deployment with more stalled processes than this
     *              has a systemic problem, and the exact figure is not what anybody needs next.
     * @return the ids; empty from implementations that cannot answer cheaply
     */
    default List<String> findStalled(LocalDateTime idleBefore, int limit) {
        return List.of();
    }

}
