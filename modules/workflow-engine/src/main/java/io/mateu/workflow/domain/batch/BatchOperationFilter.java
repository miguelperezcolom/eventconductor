package io.mateu.workflow.domain.batch;

import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Which processes a batch operation acts on — the process listing's filter, as it was given: the
 * definition, the tag, the statuses, the creation window, the business key, the search text, the errors
 * toggle and the search-attribute conditions (as a query, {@code customerId = "C-9" AND amount > 100}).
 *
 * <p>Kept as given rather than resolved, and resolved again on every page the runner reads: a tag stands
 * for the definitions carrying it when the batch runs, and a query is parsed by the same parser as the
 * listing's. Every field is optional, but a batch needs at least one ({@link #isEmpty}): "every process
 * the engine has" is not something to reach by forgetting a filter.
 */
public record BatchOperationFilter(
        String workflowDefinitionId,
        String tag,
        Set<ProcessStatus> status,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        String businessKey,
        String searchText,
        boolean onlyErrors,
        String query) {

    public BatchOperationFilter {
        status = status == null || status.isEmpty() ? null : Set.copyOf(status);
        workflowDefinitionId = blankToNull(workflowDefinitionId);
        tag = blankToNull(tag);
        businessKey = blankToNull(businessKey);
        searchText = blankToNull(searchText);
        query = blankToNull(query);
    }

    /** Whether nothing narrows it — which a batch refuses. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return workflowDefinitionId == null && tag == null && status == null && createdFrom == null
                && createdTo == null && businessKey == null && searchText == null && !onlyErrors && query == null;
    }

    /** One line, for lists and logs. */
    public String describe() {
        var parts = new java.util.ArrayList<String>();
        if (workflowDefinitionId != null) parts.add("definition " + workflowDefinitionId);
        if (tag != null) parts.add("tag " + tag);
        if (status != null) parts.add("status " + status.stream().map(Enum::name).sorted().toList());
        if (onlyErrors) parts.add("only errors");
        if (businessKey != null) parts.add("business key ~ " + businessKey);
        if (searchText != null) parts.add("text ~ " + searchText);
        if (createdFrom != null) parts.add("created ≥ " + createdFrom);
        if (createdTo != null) parts.add("created ≤ " + createdTo);
        if (query != null) parts.add(query);
        return parts.isEmpty() ? "—" : String.join("; ", parts);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
