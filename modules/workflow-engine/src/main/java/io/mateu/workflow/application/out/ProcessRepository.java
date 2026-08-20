package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.time.LocalDateTime;
import java.util.Comparator;
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

}
