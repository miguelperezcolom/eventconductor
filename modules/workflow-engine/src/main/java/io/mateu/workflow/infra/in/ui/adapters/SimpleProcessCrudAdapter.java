package io.mateu.workflow.infra.in.ui.adapters;


import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.application.out.ProcessSummary;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.in.ui.pages.process.CreateProcessForm;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessFilters;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessNotFoundView;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessRow;
import io.mateu.workflow.infra.in.ui.pages.process.SimpleProcessViewModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static io.mateu.uidl.Humanizer.toUpperCaseFirst;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@RequiredArgsConstructor
public class SimpleProcessCrudAdapter  {

    final SimpleProcessViewModel model;
    final ProcessRepository repository;
    final ObjectProvider<io.mateu.workflow.application.out.ProcessIndexRepository> processIndex;
    final ProcessStatusAnnouncer readModel;
    final ObjectProvider<CreateProcessForm> createProcessFormProvider;
    /** Resolves a tag filter to the definitions that carry it. */
    final ObjectProvider<io.mateu.workflow.application.out.WorkflowDefinitionRepository> definitions;
    final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ListingData<ProcessRow> search(String searchText, ProcessFilters filters, Pageable pageable, HttpRequest httpRequest) {
        var filter = listingFilter(searchText, filters, httpRequest);
        // Where the page comes from — the process index when it is on and can answer, this shard's
        // own database otherwise — is decided by the listing service, which the REST API uses too.
        // Filtering, ordering and paging all happen in the store: doing them here meant loading every
        // process, and with it every process's workflow definition JSON, to paint ten rows.
        var found = listing().search(filter, pageable == null ? 0 : pageable.page(),
                pageable == null ? 0 : pageable.size());
        List<ProcessRow> page = found.content().stream().map(mapSummaryToRow(dtf)).toList();
        // The page number and size reported back are the ones the store served, not the ones that
        // were asked for. A request past the last page is answered with the last real one, and a
        // request carrying no size is answered with everything — both are numbers the pager on the
        // other end divides by, and a 0 there read as "Page 3423 of Infinity".
        return new ListingData<>(new Page<>(
                searchText, found.pageSize(), found.pageNumber(), found.totalElements(), page));
    }

    /** The shared listing, over this adapter's stores. */
    private io.mateu.workflow.application.services.ProcessListingService listing() {
        return new io.mateu.workflow.application.services.ProcessListingService(
                repository, processIndex, readModel, definitions);
    }

    /**
     * The filter, from the record when there is one and from the component state when there is not.
     *
     * <p>Both paths exist because {@code SearchActionHandler} passes {@code filters = null} and the
     * values then travel only in the component state, while other routes arrive with the record
     * filled in. Reading one and not the other gives a filter that works until you type in the
     * search box.
     */
    io.mateu.workflow.application.out.ProcessListingFilter listingFilter(
            String searchText, ProcessFilters filters, HttpRequest httpRequest) {
        if (filters != null) {
            return new io.mateu.workflow.application.out.ProcessListingFilter(
                    searchText,
                    Boolean.TRUE.equals(filters.onlyErrors()),
                    blankToNull(filters.workflowDefinitionId()),
                    filters.status(),
                    filters.createdFrom(),
                    filters.createdTo(),
                    blankToNull(filters.businessKey()),
                    definitionsTagged(blankToNull(filters.tag())),
                    io.mateu.workflow.search.SearchQuery.parse(filters.attributes()));
        }
        return new io.mateu.workflow.application.out.ProcessListingFilter(
                searchText,
                stateFlag(httpRequest, "onlyErrors"),
                blankToNull(stateString(httpRequest, "workflowDefinitionId")),
                stateStatus(httpRequest),
                stateDateTime(httpRequest, "createdFrom"),
                stateDateTime(httpRequest, "createdTo"),
                blankToNull(stateString(httpRequest, "businessKey")),
                definitionsTagged(blankToNull(stateString(httpRequest, "tag"))),
                io.mateu.workflow.search.SearchQuery.parse(stateString(httpRequest, "attributes")));
    }

    /**
     * The Processes page's filter as a batch operation keeps it — the tag and the attribute query as typed,
     * resolved again whenever the batch reads a page — from the component state the toolbar action carries.
     */
    public io.mateu.workflow.domain.batch.BatchOperationFilter batchFilter(HttpRequest httpRequest) {
        return new io.mateu.workflow.domain.batch.BatchOperationFilter(
                stateString(httpRequest, "workflowDefinitionId"),
                stateString(httpRequest, "tag"),
                stateStatus(httpRequest),
                stateDateTime(httpRequest, "createdFrom"),
                stateDateTime(httpRequest, "createdTo"),
                stateString(httpRequest, "businessKey"),
                stateString(httpRequest, "searchText"),
                stateFlag(httpRequest, "onlyErrors"),
                stateString(httpRequest, "attributes"));
    }

    /** A tag that no definition carries must list nothing: a set no process can match, never "no filter". */
    static final String NO_DEFINITION = io.mateu.workflow.application.services.ProcessListingService.NO_DEFINITION;

    /** The ids of the definitions carrying {@code tag}; null for no tag. */
    java.util.Set<String> definitionsTagged(String tag) {
        return listing().definitionsTagged(tag);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Object state(HttpRequest httpRequest, String name) {
        if (httpRequest == null || httpRequest.runActionRq() == null
                || httpRequest.runActionRq().componentState() == null) {
            return null;
        }
        return httpRequest.runActionRq().componentState().get(name);
    }

    private static String stateString(HttpRequest httpRequest, String name) {
        var value = state(httpRequest, name);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The chosen statuses, from a component state that arrives as a comma-joined string (the
     * multi-select restores {@code ?status=PAUSED,COMPLETED} that way). Each token is trimmed and
     * parsed; an unrecognised one is dropped rather than failing, because the value can outlive the
     * enum it was chosen from and a stale bookmark should show everything rather than a stack trace.
     * Blank input, or every token invalid, narrows nothing (null).
     */
    private static java.util.Set<io.mateu.workflow.domain.aggregates.ProcessStatus> stateStatus(HttpRequest httpRequest) {
        var raw = blankToNull(stateString(httpRequest, "status"));
        if (raw == null) {
            return null;
        }
        var statuses = new java.util.LinkedHashSet<io.mateu.workflow.domain.aggregates.ProcessStatus>();
        for (var token : raw.split(",")) {
            var trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                statuses.add(io.mateu.workflow.domain.aggregates.ProcessStatus.valueOf(trimmed));
            } catch (IllegalArgumentException ignored) {
                // A stale token from a since-removed status narrows nothing rather than failing.
            }
        }
        return statuses.isEmpty() ? null : statuses;
    }

    /** Same tolerance, same reason. A date-only value is read as that day's first moment. */
    private static java.time.LocalDateTime stateDateTime(HttpRequest httpRequest, String name) {
        var raw = blankToNull(stateString(httpRequest, name));
        if (raw == null) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return java.time.LocalDate.parse(raw).atStartOfDay();
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
    }

    static boolean stateFlag(HttpRequest httpRequest, String name) {
        if (httpRequest == null || httpRequest.runActionRq() == null
                || httpRequest.runActionRq().componentState() == null) {
            return false;
        }
        return Boolean.TRUE.equals(httpRequest.runActionRq().componentState().get(name));
    }

    private static Function<Process, ProcessRow> mapProcessToRow(DateTimeFormatter dtf) {
        return process -> mapSummaryToRow(dtf).apply(ProcessSummary.from(process));
    }

    private static Function<ProcessSummary, ProcessRow> mapSummaryToRow(DateTimeFormatter dtf) {
        // A row projected into the index before it carried a name has none: rather than render a
        // blank where the operator looks, it shows the business key — the other thing that
        // identifies a process to a person.
        return summary -> new ProcessRow(summary.id(),
                summary.name() != null ? summary.name() : summary.businessKey(),
                mapProcessStatus(summary.status(), summary.completionPercentage()),
                summary.created() != null ? summary.created().format(dtf) : null,
                summary.started() != null ? summary.started().format(dtf) : null,
                summary.finished() != null ? summary.finished().format(dtf) : null);
    }

    public static Status mapProcessStatus(ProcessStatus status, int completionPercentage) {
        StatusType statusType = switch (status) {
            case PENDING -> StatusType.INFO;
            case RUNNING, PAUSED -> StatusType.WARNING;
            // COMPENSATED is a success, and reads as one. The business outcome is not the happy
            // one, but the process did exactly what it was written to do: it failed, and every
            // step that had run was undone, in order, to the end. Amber said "something needs
            // looking at" about a saga that had already cleaned up after itself — and it sat in a
            // list next to the ERROR processes that really do.
            case COMPLETED, COMPENSATED -> StatusType.SUCCESS;
            // COMPENSATION_FAILED is a real failure that reads as one — the rollback could not
            // finish and the process is left partially undone. It belongs next to ERROR, unlike
            // COMPENSATED which cleaned up after itself.
            case CANCELLED, ERROR, COMPENSATION_FAILED -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()) + " (" + completionPercentage + "%)");
    }

    public Object getCreationForm(HttpRequest httpRequest) {
        return createProcessFormProvider.getObject();
    }

    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    public Object getView(String id, HttpRequest httpRequest) {
        Process process = repository.findById(id).orElse(repository.findByBusinessKey(id).orElse(null));
        if (process == null) {
            // A page, not a payload: a Data is a wire fragment, and returning one here had the view
            // titled — window included — after its Java toString().
            return new ProcessNotFoundView();
        }
        httpRequest.setAttribute("_process", process);
        httpRequest.setAttribute("_status", process.getStatus().name());
        httpRequest.setAttribute("_returnTo", httpRequest.getParameterValue("returnTo"));
        return model.load(process.id(), httpRequest);
    }

    public CrudStore<ProcessRow> repository() {
        return new CrudStore<ProcessRow>() {
            @Override
            public Optional<ProcessRow> findById(String id) {
                return repository.findById(id).map(p -> mapProcessToRow(dtf).apply(p));
            }

            @Override
            public String save(ProcessRow entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ProcessRow> findAll() {
                return repository.findAll().stream().map(p -> mapProcessToRow(dtf).apply(p)).toList();
            }

            @Override
            public void deleteAllById(List<String> selectedIds) {
                repository.deleteAllById(selectedIds);
            }
        };
    }
}
