package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.Data;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessNotFoundView;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessRow;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import org.springframework.beans.factory.ObjectProvider;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the process listing reports about the page it just served — the numbers the pager on the
 * other end divides by.
 */
class SimpleProcessCrudAdapterTest {

    private final ProcessRepository repository = mock(ProcessRepository.class);

    /**
     * The read model off, which is every deployment that has not turned it on — and the case that
     * matters: its adapter is wired whenever persistence is jpa, so a listing that asked the index
     * without checking would query an empty table and report no processes at all. These assertions
     * are all about the write-side path, so they only hold if that check is there.
     */
    private final ProcessStatusAnnouncer readModel = new ProcessStatusAnnouncer(false, "");

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ProcessIndexRepository> index = mock(ObjectProvider.class);

    private SimpleProcessCrudAdapter adapter(int processes) {
        // The listing pages in the store now. Its in-memory implementation is a default method over
        // findAll(), and Mockito does not run default methods — so it is routed to the real one,
        // which then reads the findAll() stub below. These assertions therefore still describe the
        // shipped behaviour rather than a reimplementation of it.
        when(repository.searchSummaries(any(), anyInt(), anyInt())).thenCallRealMethod();
        when(repository.findAll()).thenReturn(IntStream.range(0, processes)
                .mapToObj(i -> Process.builder()
                        .id("p-" + i)
                        .name("process " + i)
                        .businessKey("bk-" + i)
                        .status(ProcessStatus.COMPLETED)
                        .workflowDefinitionId("d-" + (i % 2))
                        .created(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i))
                        .build())
                .toList());
        return new SimpleProcessCrudAdapter(null, repository, index, readModel, null);
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

    /**
     * The bug this nearly shipped with. {@code ProcessIndexRepository} is wired whenever persistence
     * is jpa — projector or no projector — so an implementation that reached for it whenever the
     * bean existed would query an empty {@code process_index} and answer "no processes" on a
     * perfectly healthy deployment. A wrong answer that looks like a right one.
     */
    @Test
    void withTheReadModelOffTheIndexIsNotConsultedAtAll() {
        var indexStore = mock(ProcessIndexRepository.class);
        when(index.getIfAvailable()).thenReturn(indexStore);

        var page = page(25, 0, 10);

        assertThat(page.totalElements()).isEqualTo(25);
        assertThat(page.content()).hasSize(10);
        verifyNoInteractions(indexStore);
    }

    /**
     * With the read model on, the listing is answered from the index — which is the only store that
     * has seen every shard. The write side is not touched.
     *
     * <p>The name fallback is asserted here too: a row projected before the index carried a name has
     * none, and a blank in the column an operator reads is worse than the business key that
     * identifies the same process.
     */
    @Test
    void withTheReadModelOnTheListingComesFromTheIndex() {
        var on = new SimpleProcessCrudAdapter(null, repository, index,
                new ProcessStatusAnnouncer(true, ""), null);
        var indexStore = mock(ProcessIndexRepository.class);
        when(index.getIfAvailable()).thenReturn(indexStore);
        when(indexStore.search(null, false, 0, 10)).thenReturn(java.util.Optional.of(
                new ProcessIndexRepository.ProcessIndexPage(List.of(
                        indexRow("i-1", "bk-1", "named process"),
                        indexRow("i-2", "bk-2", null)),
                        7, 0, 10)));

        var page = on.search(null, null, new Pageable(0, 10, List.of()), null).page();

        assertThat(page.totalElements()).isEqualTo(7);
        assertThat(page.content()).extracting(r -> ((ProcessRow) r).id()).containsExactly("i-1", "i-2");
        assertThat(page.content()).extracting(r -> ((ProcessRow) r).name())
                .as("a row with no projected name falls back to its business key")
                .containsExactly("named process", "bk-2");
        verifyNoInteractions(repository);
    }

    private static io.mateu.workflow.application.readmodel.ProcessIndexRow indexRow(
            String id, String businessKey, String name) {
        return new io.mateu.workflow.application.readmodel.ProcessIndexRow(
                id, businessKey, name, "wd-1", 1, "COMPLETED", 100,
                LocalDateTime.of(2026, 1, 1, 0, 0), null, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }

    @Test
    void findsNothingWithoutFallingOver() {
        var page = page(0, 7, 10);

        assertThat(page.totalElements()).isZero();
        assertThat(page.content()).isEmpty();
        assertThat(page.pageNumber()).isZero();
    }

    /**
     * A link to a process that is not here any more — a retention sweep, a regenerated dataset, a
     * link pointing at another environment. It is an ordinary destination, so it has to answer with
     * a PAGE.
     *
     * <p>It used to answer with a {@code Data}, which is a wire fragment meant to come back from an
     * action: the crud built a view around it and titled the page — browser tab included — after its
     * Java toString, so a stale link read
     * "Data[data={error=Process not found}, style=, cssClasses=, newState=null]".
     */
    @Test
    void anIdThatIsNotHereIsAnsweredWithAPageAndNotWithAWireFragment() {
        var view = adapter(0).getView("no-such-process", null);

        assertThat(view).isInstanceOf(ProcessNotFoundView.class);
        assertThat(view).isNotInstanceOf(Data.class);
    }
}
