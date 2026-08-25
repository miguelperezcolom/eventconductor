package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.ProcessSummary;
import io.mateu.workflow.application.out.StepExecutionSummary;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The process and step-execution listings against a real database. The queries behind them live in
 * JPQL, which the compiler never sees, so this is what proves them: the projections bind, the
 * "order by ... desc nulls last" parses, the count query Spring derives is a valid one, and the
 * filters mean what the in-memory implementations mean.
 *
 * <p>The listings used to load the whole table and page in Java. On a deployment with 37 651
 * processes that was 315 MB pulled out of Postgres to paint ten rows, and step executions — the
 * engine's largest table — were worse. Nothing here measures that; what it pins is the behaviour
 * the pushed-down version has to keep.
 */
class ListingPaginationJpaE2eTest extends AbstractJpaE2eTest {

    @Test
    void pagesProcessesNewestFirstWithATotalIndependentOfThePage() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "page-a");
        awaitStatus("page-a", ProcessStatus.COMPLETED);
        createProcess("sequential-3", "page-b");
        awaitStatus("page-b", ProcessStatus.COMPLETED);
        createProcess("sequential-3", "page-c");
        awaitStatus("page-c", ProcessStatus.COMPLETED);

        var first = processRepository.searchSummaries(null, false, 0, 2);
        var second = processRepository.searchSummaries(null, false, 1, 2);

        assertThat(first.content()).hasSize(2);
        assertThat(second.content()).hasSize(1);
        // The total is the size of the match, not of the page — that is the whole point of paging
        // in SQL: the caller never sees, and never pays for, the rows it did not ask for.
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(second.totalElements()).isEqualTo(3);

        var paged = concat(first.content(), second.content());
        assertThat(paged).extracting(ProcessSummary::id).doesNotHaveDuplicates();
        assertThat(paged)
                .as("newest first, and the pages must partition the result in that order")
                .isSortedAccordingTo(Comparator.comparing(ProcessSummary::created).reversed());
    }

    @Test
    void filtersProcessesByTextAndByErrorStatusInTheStore() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());
        worker.on("flaky", TestWorker.fail());

        createProcess("sequential-3", "keep-me");
        awaitStatus("keep-me", ProcessStatus.COMPLETED);
        createProcess("retry", "broken-one");
        awaitStatus("broken-one", ProcessStatus.ERROR);

        // Business key, case-insensitively — the same text the in-memory store searches.
        var byKey = processRepository.searchSummaries("KEEP-ME", false, 0, 10);
        assertThat(byKey.totalElements()).isEqualTo(1);
        assertThat(byKey.content()).singleElement()
                .extracting(ProcessSummary::status).isEqualTo(ProcessStatus.COMPLETED);

        // Definition name, so a search is not limited to keys the operator has memorised.
        assertThat(processRepository.searchSummaries("retry then succeed", false, 0, 10).totalElements())
                .isEqualTo(1);

        var onlyErrors = processRepository.searchSummaries(null, true, 0, 10);
        assertThat(onlyErrors.totalElements()).isEqualTo(1);
        assertThat(onlyErrors.content()).singleElement()
                .extracting(ProcessSummary::status).isEqualTo(ProcessStatus.ERROR);

        // Text and status filters compose rather than replacing one another.
        assertThat(processRepository.searchSummaries("keep-me", true, 0, 10).totalElements()).isZero();
    }

    @Test
    void pagesStepExecutionsMostRecentlyStartedFirst() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "steps-1");
        awaitStatus("steps-1", ProcessStatus.COMPLETED);

        var processId = process("steps-1").id();
        var all = stepExecutionRepository.searchSummaries(null, false, 0, 100);
        assertThat(all.totalElements()).isEqualTo(steps("steps-1").size());
        assertThat(all.content())
                .as("most recently started first; steps that never started sort last")
                .isSortedAccordingTo(Comparator.comparing(
                        StepExecutionSummary::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        var firstPage = stepExecutionRepository.searchSummaries(null, false, 0, 1);
        assertThat(firstPage.content()).hasSize(1);
        assertThat(firstPage.totalElements()).isEqualTo(all.totalElements());

        // Searching by process id is how an operator gets from a process to its steps.
        assertThat(stepExecutionRepository.searchSummaries(processId, false, 0, 100).totalElements())
                .isEqualTo(all.totalElements());
        assertThat(stepExecutionRepository.searchSummaries("no-such-process", false, 0, 100).content())
                .isEmpty();
    }

    @Test
    void keepsOnlyFailedStepExecutionsWhenAskedTo() {
        worker.on("flaky", TestWorker.fail());

        createProcess("retry", "steps-err");
        awaitStatus("steps-err", ProcessStatus.ERROR);

        var onlyErrors = stepExecutionRepository.searchSummaries(null, true, 0, 100);

        assertThat(onlyErrors.content()).isNotEmpty();
        assertThat(onlyErrors.content()).allSatisfy(summary ->
                assertThat(summary.status()).isIn(StepExecutionStatus.ERROR, StepExecutionStatus.TIMEOUT));
        assertThat(onlyErrors.totalElements()).isEqualTo(onlyErrors.content().size());
    }

    @Test
    void answersAPageBeyondTheEndWithTheLastRealOne() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "clamp-a");
        awaitStatus("clamp-a", ProcessStatus.COMPLETED);
        createProcess("sequential-3", "clamp-b");
        awaitStatus("clamp-b", ProcessStatus.COMPLETED);
        createProcess("sequential-3", "clamp-c");
        awaitStatus("clamp-c", ProcessStatus.COMPLETED);

        // A stale deep link, of the kind that reported a page size of 0 and left the pager
        // dividing by it. Pushed down to SQL this cannot be clamped after the fact — the store has
        // to count before it knows which page exists — so it is worth pinning on the JPA path too
        // and not only on the in-memory one.
        var beyondTheEnd = processRepository.searchSummaries(null, false, 3422, 2);

        assertThat(beyondTheEnd.pageNumber()).isEqualTo(1);
        assertThat(beyondTheEnd.pageSize()).isEqualTo(2);
        assertThat(beyondTheEnd.totalElements()).isEqualTo(3);
        assertThat(beyondTheEnd.content()).hasSize(1);

        // No size asked for is everything, on one page — never a size of zero.
        var everything = processRepository.searchSummaries(null, false, 0, 0);
        assertThat(everything.pageSize()).isEqualTo(3);
        assertThat(everything.content()).hasSize(3);

        // And the same for step executions.
        var steps = stepExecutionRepository.searchSummaries(null, false, 9999, 2);
        assertThat(steps.pageSize()).isEqualTo(2);
        assertThat(steps.content()).isNotEmpty();
        assertThat(steps.pageNumber())
                .isEqualTo((int) ((steps.totalElements() - 1) / 2));
    }

    @Test
    void reportsAnEmptyResultWithoutFallingOver() {
        var nothing = processRepository.searchSummaries("no-such-process-anywhere", false, 7, 10);

        assertThat(nothing.totalElements()).isZero();
        assertThat(nothing.content()).isEmpty();
        assertThat(nothing.pageNumber()).isZero();
        // Not zero: the pager divides by it.
        assertThat(nothing.pageSize()).isEqualTo(10);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
