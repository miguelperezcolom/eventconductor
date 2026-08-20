package io.mateu.workflow.infra.in.ui.adapters;


import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessSummary;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.in.ui.pages.process.CreateProcessForm;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessFilters;
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
    final ObjectProvider<CreateProcessForm> createProcessFormProvider;
    final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ListingData<ProcessRow> search(String searchText, ProcessFilters filters, Pageable pageable, HttpRequest httpRequest) {
        // SearchActionHandler passes filters = null; the filter values only travel in the component state
        boolean onlyErrors = filters != null && Boolean.TRUE.equals(filters.onlyErrors())
                || filters == null && stateFlag(httpRequest, "onlyErrors");
        // Filtering, ordering and paging all happen in the store. Doing them here meant loading
        // every process — and with it every process's workflow definition JSON — to paint ten rows.
        var found = repository.searchSummaries(searchText, onlyErrors, pageable.page(), pageable.size());
        List<ProcessRow> page = found.content().stream().map(mapSummaryToRow(dtf)).toList();
        // The page number and size reported back are the ones the store served, not the ones that
        // were asked for. A request past the last page is answered with the last real one, and a
        // request carrying no size is answered with everything — both are numbers the pager on the
        // other end divides by, and a 0 there read as "Page 3423 of Infinity".
        return new ListingData<>(new Page<>(
                searchText, found.pageSize(), found.pageNumber(), found.totalElements(), page));
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
        return summary -> new ProcessRow(summary.id(),
                summary.name(),
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
            return new Data(Map.of("error", "Process not found"));
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
