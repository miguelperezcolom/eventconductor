package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The listing filter, and the in-memory store applying all of it.
 *
 * <p>The store side is the half worth pinning. A filter that a store quietly ignores shows a page
 * that does not answer the question that was asked, and there is nothing on screen to say so — so
 * each field gets an assertion that it actually narrows, and one that it does not narrow what it
 * should not.
 */
class ProcessListingFilterTest {

    private static final LocalDateTime JAN_1 = LocalDateTime.of(2026, 1, 1, 10, 0);

    /**
     * The store as its default methods over a fixed list. Mockito does not run default methods, so
     * {@code thenCallRealMethod} routes to the shipped one — these assertions therefore describe
     * the real implementation rather than a copy of it. Same arrangement as
     * {@code SimpleProcessCrudAdapterTest}.
     */
    private static ProcessRepository storeOf(List<Process> processes) {
        var repository = mock(ProcessRepository.class);
        when(repository.searchSummaries(any(), anyInt(), anyInt())).thenCallRealMethod();
        when(repository.findAll()).thenReturn(processes);
        return repository;
    }

    private static Process process(String id, String definitionId, ProcessStatus status,
                                   LocalDateTime created) {
        return Process.builder()
                .id(id)
                .name(id)
                .businessKey("bk-" + id)
                .workflowDefinitionId(definitionId)
                .status(status)
                .created(created)
                .build();
    }

    private static final List<Process> SAMPLE = List.of(
            process("a", "orders", ProcessStatus.COMPLETED, JAN_1),
            process("b", "orders", ProcessStatus.ERROR, JAN_1.plusDays(1)),
            process("c", "refunds", ProcessStatus.RUNNING, JAN_1.plusDays(2)));

    private static List<String> ids(ProcessListingFilter filter) {
        return storeOf(SAMPLE).searchSummaries(filter, 0, 50).content().stream()
                .map(ProcessSummary::id)
                .sorted()
                .toList();
    }

    @Nested
    @DisplayName("each field narrows")
    class Narrows {

        @Test
        void byDefinition() {
            assertThat(ids(new ProcessListingFilter(null, false, "orders", null, null, null)))
                    .containsExactly("a", "b");
        }

        @Test
        void byStatus() {
            assertThat(ids(new ProcessListingFilter(null, false, null, ProcessStatus.RUNNING, null, null)))
                    .containsExactly("c");
        }

        @Test
        void byCreatedFrom() {
            assertThat(ids(new ProcessListingFilter(null, false, null, null, JAN_1.plusDays(1), null)))
                    .containsExactly("b", "c");
        }

        @Test
        void byCreatedTo() {
            assertThat(ids(new ProcessListingFilter(null, false, null, null, null, JAN_1.plusDays(1))))
                    .containsExactly("a", "b");
        }

        /** Both ends are inclusive, which is what the field's help text promises. */
        @Test
        void byBothEndsInclusively() {
            assertThat(ids(new ProcessListingFilter(null, false, null, null,
                    JAN_1.plusDays(1), JAN_1.plusDays(1))))
                    .containsExactly("b");
        }

        @Test
        void andTheyCompose() {
            assertThat(ids(new ProcessListingFilter(null, false, "orders", ProcessStatus.ERROR, null, null)))
                    .containsExactly("b");
        }
    }

    @Nested
    @DisplayName("nothing set narrows nothing")
    class NarrowsNothing {

        @Test
        void anEmptyFilterReturnsEverything() {
            assertThat(ids(ProcessListingFilter.of(null, false))).containsExactly("a", "b", "c");
        }

        @Test
        void blankTextIsNoTextFilter() {
            assertThat(ids(ProcessListingFilter.of("   ", false))).containsExactly("a", "b", "c");
        }

        /** The pre-existing toggle keeps working alongside the new fields. */
        @Test
        void onlyErrorsStillWorks() {
            assertThat(ids(ProcessListingFilter.of(null, true))).containsExactly("b");
        }
    }

    @Nested
    @DisplayName("what the read model may answer")
    class ReadModelHandover {

        /**
         * The index projection can apply text and the errors toggle and nothing else, so it has to
         * be able to recognise when it must stand aside — otherwise it serves a page that ignores
         * half of what was asked.
         */
        @Test
        void textAndErrorsAloneCanBeAnsweredByTheIndex() {
            assertThat(ProcessListingFilter.of("x", true).hasNarrowingBeyondText()).isFalse();
        }

        @Test
        void anythingElseCannot() {
            assertThat(new ProcessListingFilter(null, false, "orders", null, null, null)
                    .hasNarrowingBeyondText()).isTrue();
            assertThat(new ProcessListingFilter(null, false, null, ProcessStatus.ERROR, null, null)
                    .hasNarrowingBeyondText()).isTrue();
            assertThat(new ProcessListingFilter(null, false, null, null, JAN_1, null)
                    .hasNarrowingBeyondText()).isTrue();
            assertThat(new ProcessListingFilter(null, false, null, null, null, JAN_1)
                    .hasNarrowingBeyondText()).isTrue();
        }
    }
}
