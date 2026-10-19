package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessListingFilter;
import io.mateu.workflow.processindex.SearchAttributeConditions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The process listing's {@code WHERE} as JPQL built for the filter at hand: only the conditions that are
 * set are written, every value is a named parameter, and each search-attribute condition is an
 * {@code EXISTS} over {@code process_search_attribute} (see {@link SearchAttributeConditions}).
 *
 * <p>What the fixed {@code @Query} of {@link ProcessEntityRepository#searchSummaries} cannot be: it has a
 * slot per field, and a list of attribute conditions has no fixed number of slots. The fixed query keeps
 * answering every filter without attributes; this one answers those with them, and the key-ordered walk
 * of a batch operation ({@code findIdsMatching}). The conditions are the same ones, field for field.
 */
final class ProcessListingJpql {

    /** The clause, starting with {@code where}, and its parameters by name. */
    record Where(String text, Map<String, Object> params) {
    }

    private ProcessListingJpql() {
    }

    static Where of(ProcessListingFilter filter) {
        var text = new StringBuilder(" where 1 = 1");
        var params = new LinkedHashMap<String, Object>();
        if (filter.onlyErrors()) {
            text.append(" and p.status = 'ERROR'");
        }
        var searchText = filter.normalisedSearchText();
        if (searchText != null) {
            text.append(" and lower(concat(coalesce(p.name, ''), ' ', coalesce(p.businessKey, p.releasedBusinessKey, ''))) like :pattern");
            params.put("pattern", "%" + searchText.toLowerCase() + "%");
        }
        var businessKey = filter.normalisedBusinessKey();
        if (businessKey != null) {
            text.append(" and lower(coalesce(p.businessKey, p.releasedBusinessKey, '')) like :businessKey");
            params.put("businessKey", "%" + businessKey.toLowerCase() + "%");
        }
        if (filter.workflowDefinitionId() != null) {
            text.append(" and p.workflowDefinitionId = :definitionId");
            params.put("definitionId", filter.workflowDefinitionId());
        }
        if (filter.normalisedDefinitionIds() != null) {
            text.append(" and p.workflowDefinitionId in :definitionIds");
            params.put("definitionIds", filter.normalisedDefinitionIds());
        }
        if (filter.normalisedStatuses() != null) {
            text.append(" and p.status in :statuses");
            params.put("statuses", filter.normalisedStatuses().stream().map(Enum::name).toList());
        }
        if (filter.createdFrom() != null) {
            text.append(" and p.created >= :createdFrom");
            params.put("createdFrom", filter.createdFrom());
        }
        if (filter.createdTo() != null) {
            text.append(" and p.created <= :createdTo");
            params.put("createdTo", filter.createdTo());
        }
        var attributes = SearchAttributeConditions.of(SearchAttributeConditions.Dialect.JPQL,
                "ProcessSearchAttributeEntity", "p.id", filter.normalisedSearchAttributes(), "a");
        text.append(attributes.text());
        params.putAll(attributes.named());
        return new Where(text.toString(), params);
    }
}
