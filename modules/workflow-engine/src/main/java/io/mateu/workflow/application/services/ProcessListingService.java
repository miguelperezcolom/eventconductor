package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessListingFilter;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessSummary;
import io.mateu.workflow.application.out.ProcessSummaryPage;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The process listing — one page of processes for a filter — for every door that lists them: the
 * admin console's Processes page and the REST API ({@code GET /workflow/api/processes}).
 *
 * <p>It decides where the page comes from. The CQRS process index answers when it is on and the
 * filter is one it can answer (text, the errors toggle and — since 2.30 — search attributes); the write
 * side answers everything else.
 * The index holds a projection, not the process, so rather than serve a page that quietly ignores the
 * definition, status or dates that were asked for, it declines and the write side answers. In a
 * sharded fleet that is the difference between listing the fleet and listing the shard that served
 * the request.
 *
 * <p>The read model being ON is the question, not the index adapter existing: the adapter is wired
 * whenever persistence is jpa, projector or no projector, and asking it with the projector off
 * queries an empty table and answers "no processes".
 */
@Service
public class ProcessListingService {

    /** A tag that no definition carries must list nothing: a set no process can match, never "no filter". */
    public static final String NO_DEFINITION = "~no-definition-carries-this-tag~";

    private final ProcessRepository repository;
    private final ObjectProvider<ProcessIndexRepository> processIndex;
    private final ProcessStatusAnnouncer readModel;
    private final ObjectProvider<WorkflowDefinitionRepository> definitions;

    public ProcessListingService(ProcessRepository repository,
                                 ObjectProvider<ProcessIndexRepository> processIndex,
                                 ProcessStatusAnnouncer readModel,
                                 ObjectProvider<WorkflowDefinitionRepository> definitions) {
        this.repository = repository;
        this.processIndex = processIndex;
        this.readModel = readModel;
        this.definitions = definitions;
    }

    /**
     * One page of the processes the filter matches, newest first. The page number and size in the
     * answer are the ones the store served: a request past the last page is answered with the last
     * real one, and a request with no size with everything on one page.
     */
    public ProcessSummaryPage search(ProcessListingFilter filter, int page, int size) {
        // A tag no definition carries lists nothing. Answered here, not by the store: the set it would
        // be asked to match by has no member, and an empty IN list is not portable SQL.
        if (filter.workflowDefinitionIds() != null && filter.workflowDefinitionIds().contains(NO_DEFINITION)) {
            return new ProcessSummaryPage(List.of(), 0, 0, size <= 0 ? 1 : size);
        }
        return searchIndex(filter, page, size).orElseGet(() -> repository.searchSummaries(filter, page, size));
    }

    /** Whether a listing with this filter would be answered from the process index. */
    public boolean answeredFromIndex(ProcessListingFilter filter) {
        return !filter.hasNarrowingBeyondTextAndAttributes() && readModel.isEnabled()
                && processIndex.getIfAvailable() != null;
    }

    private Optional<ProcessSummaryPage> searchIndex(ProcessListingFilter filter, int page, int size) {
        if (!answeredFromIndex(filter)) {
            return Optional.empty();
        }
        // The store may still decline (it cannot page, or cannot match search attributes), by answering
        // empty rather than a page it had to load whole or that ignores half the filter.
        var attributes = filter.normalisedSearchAttributes();
        var index = processIndex.getIfAvailable();
        var answer = attributes == null
                ? index.search(filter.normalisedSearchText(), filter.onlyErrors(), page, size)
                : index.search(filter.normalisedSearchText(), filter.onlyErrors(), attributes, page, size);
        return answer
                .map(found -> new ProcessSummaryPage(
                        found.content().stream().map(ProcessListingService::summaryOf).toList(),
                        found.totalElements(), found.pageNumber(), found.pageSize()));
    }

    /**
     * The ids of the definitions carrying {@code tag} (case-insensitively); null for no tag, and
     * {@link #NO_DEFINITION} alone when no definition carries it.
     */
    public Set<String> definitionsTagged(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        var repository = definitions == null ? null : definitions.getIfAvailable();
        var ids = repository == null ? Set.<String>of() : repository.findAll().stream()
                .filter(wd -> wd.hasTag(tag))
                .map(WorkflowDefinition::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return ids.isEmpty() ? Set.of(NO_DEFINITION) : ids;
    }

    private static ProcessSummary summaryOf(ProcessIndexRow row) {
        return new ProcessSummary(row.processId(), row.name(), ProcessStatus.valueOf(row.status()),
                row.completionPercentage(), row.created(), row.started(), row.finished(),
                row.businessKey(), row.workflowDefinitionId());
    }
}
