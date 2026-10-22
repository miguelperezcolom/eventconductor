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

    record ChildrenPage(List<Process> content, int page, int size, long totalElements) {}

    /**
     * Direct descendants only; iteration/retry identity remains on parentStepExecutionId.
     *
     * <p>{@code parentStepIds} are the parent's own step executions (already narrowed to {@code stepId}
     * by the caller), so they scope the page to {@code parentProcessId}. This in-memory default scans
     * {@code findAll()}, which suits memory mode only; the JPA store answers with an indexed join.
     */
    default ChildrenPage findChildren(String parentProcessId, java.util.Set<String> parentStepIds, String stepId, int page, int size) {
        var children = findAll().stream().filter(process -> parentStepIds.contains(process.getParentStepExecutionId()))
                .sorted(Comparator.comparing(Process::getCreated, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Process::getId)).toList();
        int start = (int) Math.min((long) page * size, children.size());
        return new ChildrenPage(children.subList(start, Math.min(start + size, children.size())), page, size, children.size());
    }


    /**
     * The process that holds this business key now — the only one, unless the definition's
     * {@code businessKeyPolicy} or a CONTINUE_AS_NEW let later runs take the key over, in which case it is
     * the latest of them.
     */
    Optional<Process> findByBusinessKey(String businessKey);

    /**
     * Makes the run {@code processId} let go of its business key, now — so a later run of the chain can take it,
     * in this same unit of work. A database store writes it before returning: the key is unique, and the new run's
     * insert must not be flushed ahead of this update.
     */
    default void releaseBusinessKey(String processId) {
        findById(processId).ifPresent(process -> {
            process.releaseBusinessKey();
            save(process);
        });
    }

    /** Writes what this unit of work has saved so far (a no-op where saves are immediate). */
    default void flush() {
    }

    /** Every run that has had this business key, oldest first (2.33: a key may name a chain of runs). */
    default List<Process> findAllByBusinessKey(String businessKey) {
        if (businessKey == null) {
            return List.of();
        }
        return findAll().stream().filter(process -> businessKey.equals(process.getBusinessKey()))
                .sorted(java.util.Comparator.comparing(Process::getCreated,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Which of these processes have recorded a reply — the one question the pods waiting on
     * synchronous invocations ask, all their waiters at once, every poll. The default reads each
     * process; the JPA store answers it with one query over the ids.
     */
    default java.util.Set<String> findRepliedAmong(java.util.Collection<String> processIds) {
        var replied = new java.util.HashSet<String>();
        for (var id : processIds) {
            findById(id).filter(Process::hasReplied).ifPresent(process -> replied.add(id));
        }
        return replied;
    }

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
                .filter(process -> !process.isTestRun())
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

    /**
     * One page of the process listing.
     *
     * <p>Every field of the filter is applied here, including the ones a store might find
     * inconvenient: a listing that silently drops one shows a page that does not answer the
     * question that was asked, and nothing on screen says so.
     */
    default ProcessSummaryPage searchSummaries(ProcessListingFilter filter, int page, int size) {
        var matching = findAll().stream()
                .filter(listingPredicate(filter))
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
     * The ids of the processes the filter matches that sort after {@code afterId}, in id order, at most
     * {@code limit} — how a batch operation walks a filter: by key, not by page number, so processes
     * leaving the filter as the batch acts on them (cancelled, retried) do not shift the pages under it,
     * and a batch resumed after a restart carries on from the last id it reached.
     *
     * <p>This default is the in-memory one; the JPA store runs it as one query.
     *
     * @param afterId exclusive lower bound; null starts from the first
     */
    default List<String> findIdsMatching(ProcessListingFilter filter, String afterId, int limit) {
        return findAll().stream()
                .filter(process -> afterId == null || process.getId().compareTo(afterId) > 0)
                .filter(listingPredicate(filter))
                .map(Process::getId)
                .sorted()
                .limit(Math.max(0, limit))
                .toList();
    }

    /** Every field of the filter, as a test on a loaded process — the in-memory stores' listing. */
    static java.util.function.Predicate<Process> listingPredicate(ProcessListingFilter filter) {
        var needle = filter.normalisedSearchText() == null
                ? "" : filter.normalisedSearchText().toLowerCase();
        var businessKeyNeedle = filter.normalisedBusinessKey() == null
                ? null : filter.normalisedBusinessKey().toLowerCase();
        var attributes = filter.normalisedSearchAttributes();
        return process -> (!filter.onlyErrors() || ProcessStatus.ERROR.equals(process.getStatus()))
                && (filter.status() == null || filter.status().isEmpty() || filter.status().contains(process.getStatus()))
                && (filter.workflowDefinitionId() == null
                        || filter.workflowDefinitionId().equals(process.getWorkflowDefinitionId()))
                && (filter.normalisedDefinitionIds() == null
                        || filter.normalisedDefinitionIds().contains(process.getWorkflowDefinitionId()))
                && (businessKeyNeedle == null
                        || (process.getBusinessKey() != null
                                && process.getBusinessKey().toLowerCase().contains(businessKeyNeedle)))
                && (filter.createdFrom() == null && filter.createdTo() == null
                        || withinWindow(process.getCreated(), filter.createdFrom(), filter.createdTo()))
                && (needle.isEmpty() || process.searchableText().toLowerCase().contains(needle))
                // In memory the attributes are computed from the process itself: nothing to keep in step.
                && (attributes == null || io.mateu.workflow.search.SearchAttributeFilter.matchAll(attributes,
                        io.mateu.workflow.domain.services.SearchAttributeEvaluator.evaluate(process)));
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
     * <p>"No step left to run" means none {@link
     * io.mateu.workflow.domain.aggregates.StepExecutionStatus#keepsTheProcessInFlight() keeps it in
     * flight}: a step waiting out its retry backoff, or queued behind a lock, is still going.
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
