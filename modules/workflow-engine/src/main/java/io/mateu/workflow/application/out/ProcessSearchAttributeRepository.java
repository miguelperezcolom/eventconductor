package io.mateu.workflow.application.out;

import io.mateu.workflow.search.SearchAttributeValue;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Where a process's search attributes are kept to be searched: {@code process_search_attribute} on a
 * database (jpa). Memory persistence has no such store — there the attributes are computed from the
 * process when asked (see {@code SearchAttributeEvaluator}) — so callers take it as optional.
 *
 * <p>Written only at the {@code ProcessRepository.save} chokepoint, and only when the values changed.
 */
public interface ProcessSearchAttributeRepository {

    /** The attributes stored for a process; empty when it has none. */
    List<SearchAttributeValue> findByProcessId(String processId);

    /** The attributes of several processes at once, by process id (a process with none is absent). */
    Map<String, List<SearchAttributeValue>> findByProcessIds(Collection<String> processIds);

    /** Replaces what is stored for the process with {@code values} (none deletes them). */
    void replace(String processId, List<SearchAttributeValue> values);

    /** Forgets the attributes of these processes; returns how many rows went. */
    int deleteByProcessIds(Collection<String> processIds);
}
