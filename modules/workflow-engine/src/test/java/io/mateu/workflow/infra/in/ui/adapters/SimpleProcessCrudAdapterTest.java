package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the process listing reports about the page it just served — the numbers the pager on the
 * other end divides by.
 */
class SimpleProcessCrudAdapterTest {

    private final ProcessRepository repository = mock(ProcessRepository.class);

    private SimpleProcessCrudAdapter adapter(int processes) {
        // The listing pages in the store now. Its in-memory implementation is a default method over
        // findAll(), and Mockito does not run default methods — so it is routed to the real one,
        // which then reads the findAll() stub below. These assertions therefore still describe the
        // shipped behaviour rather than a reimplementation of it.
        when(repository.searchSummaries(any(), anyBoolean(), anyInt(), anyInt())).thenCallRealMethod();
        when(repository.findAll()).thenReturn(IntStream.range(0, processes)
                .mapToObj(i -> Process.builder()
                        .id("p-" + i)
                        .name("process " + i)
                        .businessKey("bk-" + i)
                        .status(ProcessStatus.COMPLETED)
                        .created(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i))
                        .build())
                .toList());
        return new SimpleProcessCrudAdapter(null, repository, null);
    }

    private io.mateu.uidl.data.Page<?> page(int processes, int pageNumber, int size) {
        return adapter(processes)
                .search(null, null, new Pageable(pageNumber, size, List.of()), null)
                .page();
    }

    @Test
    void servesTheRequestedPage() {
        var page = page(25, 1, 10);

        assertThat(page.pageNumber()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(25);
        assertThat(page.content()).hasSize(10);
    }

    /** The size asked for, not the rows that fitted — otherwise the pager recounts the pages. */
    @Test
    void theTrailingPartialPageStillReportsTheSizeItWasAskedFor() {
        var page = page(25, 2, 10);

        assertThat(page.content()).hasSize(5);
        assertThat(page.pageSize()).isEqualTo(10);
    }

    /**
     * A deep link to a page that no longer exists — the one that reported a page size of 0 and
     * left the pager dividing by it ("Page 3423 of Infinity").
     */
    @Test
    void aPageBeyondTheEndFallsBackToTheLastRealOne() {
        var page = page(25, 3422, 10);

        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.pageNumber()).isEqualTo(2);
        assertThat(page.content()).hasSize(5);
    }

    /** No size asked for (a state that carries none) is everything, on one page. */
    @Test
    void aSearchWithoutASizeIsOnePageOfEverything() {
        var page = page(25, 0, 0);

        assertThat(page.pageSize()).isEqualTo(25);
        assertThat(page.content()).hasSize(25);
    }

    @Test
    void findsNothingWithoutFallingOver() {
        var page = page(0, 7, 10);

        assertThat(page.totalElements()).isZero();
        assertThat(page.content()).isEmpty();
        assertThat(page.pageNumber()).isZero();
    }
}
