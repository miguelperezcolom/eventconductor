package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessSearchAttributeRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.services.SearchAttributeEvaluator;
import io.mateu.workflow.search.SearchAttributeValue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Keeps a process's search attributes in step with its variables. Called by {@code ProcessRepository.save}
 * — the chokepoint every change to a process goes through, start and end included — so no use case can
 * forget it: the attributes are computed (see {@link SearchAttributeEvaluator}), compared with what is
 * stored, and written only when they differ.
 *
 * <p>A process of a definition that declares no attributes costs one substring check of its definition
 * snapshot, and nothing else.
 */
@Component
public class SearchAttributeIndexer {

    /**
     * What a save found: the process's attributes now ({@code null} when its definition declares none) and
     * whether they changed with this save — what decides whether the process index hears about it.
     */
    public record Indexed(List<SearchAttributeValue> values, boolean changed) {
        public static final Indexed NONE = new Indexed(null, false);
    }

    private final ObjectProvider<ProcessSearchAttributeRepository> store;

    public SearchAttributeIndexer(ObjectProvider<ProcessSearchAttributeRepository> store) {
        this.store = store;
    }

    /** Computes the process's attributes and stores them when they changed. */
    public Indexed index(Process process) {
        if (!SearchAttributeEvaluator.declares(process)) {
            return Indexed.NONE;
        }
        var values = SearchAttributeEvaluator.evaluate(process);
        var repository = store.getIfAvailable();
        if (repository == null) {
            // Memory: nothing is stored, the listing computes them. Unchanged as far as the index is told.
            return new Indexed(values, false);
        }
        var stored = repository.findByProcessId(process.getId());
        if (SearchAttributeValue.sameValues(stored, values)) {
            return new Indexed(values, false);
        }
        repository.replace(process.getId(), values);
        return new Indexed(values, true);
    }

    /** The process's attributes as the listing sees them: stored on a database, computed in memory. */
    public List<SearchAttributeValue> current(Process process) {
        var repository = store.getIfAvailable();
        if (repository == null) {
            return SearchAttributeEvaluator.evaluate(process);
        }
        return repository.findByProcessId(process.getId());
    }

    /** The attributes of several processes, by id — stored ones only (memory: none; use {@link #current}). */
    public Map<String, List<SearchAttributeValue>> stored(Collection<String> processIds) {
        var repository = store.getIfAvailable();
        return repository == null || processIds.isEmpty() ? Map.of() : repository.findByProcessIds(processIds);
    }

    /** Whether attributes are kept in a store (a database) rather than computed. */
    public boolean isStored() {
        return store.getIfAvailable() != null;
    }
}
