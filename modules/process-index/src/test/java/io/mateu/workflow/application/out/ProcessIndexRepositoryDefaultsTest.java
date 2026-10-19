package io.mateu.workflow.application.out;

import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.search.SearchAttributeFilter;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A store that cannot match attributes declines a listing with them, and answers one without as it always did. */
class ProcessIndexRepositoryDefaultsTest {

    final ProcessIndexRepository store = new ProcessIndexRepository() {
        @Override public void upsert(ProcessIndexRow row) { }
        @Override public List<ProcessIndexRow> findByStatusIn(Collection<String> statuses) { return List.of(); }
        @Override public List<ProcessIndexRow> findByWorkflowDefinitionIdAndStatusIn(String id, Collection<String> s) { return List.of(); }
        @Override public Optional<ProcessIndexRow> findByBusinessKey(String businessKey) { return Optional.empty(); }
        @Override public Optional<ProcessIndexRow> findByProcessId(String processId) { return Optional.empty(); }
        @Override public Map<String, Long> countByStatus() { return Map.of(); }
        @Override public Optional<ProcessIndexPage> search(String text, boolean onlyErrors, int page, int size) {
            return Optional.of(new ProcessIndexPage(List.of(), 0, page, size));
        }
    };

    @Test
    void attributesAreDeclinedByDefault() {
        assertThat(store.search(null, false, List.of(), 0, 10)).isPresent();
        assertThat(store.search(null, false, null, 0, 10)).isPresent();
        assertThat(store.search(null, false, List.of(new SearchAttributeFilter("a", SearchAttributeFilter.Op.EQ, "1")), 0, 10))
                .isEmpty();
        assertThat(store.deleteByProcessIds(List.of("p"))).isZero();
    }
}
