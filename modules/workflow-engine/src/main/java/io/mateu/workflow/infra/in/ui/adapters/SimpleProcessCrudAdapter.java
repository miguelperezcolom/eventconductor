package io.mateu.workflow.infra.in.ui.adapters;


import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
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
import java.util.Comparator;
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
        List<ProcessRow> all = repository.findAll().stream()
                .filter(process -> !onlyErrors || ProcessStatus.ERROR.equals(process.getStatus()))
                .filter(process -> searchText == null || searchText.isEmpty() ||
                        process.searchableText().toLowerCase().contains(searchText.toLowerCase()))
                .map(mapProcessToRow(dtf))
                .sorted(Comparator.comparing(ProcessRow::created).reversed())
                .toList();
        // The page SIZE is the one that was asked for, not the number of rows this page happens to
        // carry — past the last page it carries none, and a 0 reaches the pager as a division by
        // zero ("Page 3423 of Infinity", with next/last enabled for ever). A requested page beyond
        // the end serves the last real one, so a stale deep link recovers instead of showing an
        // empty grid.
        int size = pageable.size() > 0 ? pageable.size() : all.size();
        int lastPage = size > 0 ? Math.max(0, (all.size() - 1) / size) : 0;
        int pageNumber = Math.min(Math.max(pageable.page(), 0), lastPage);
        List<ProcessRow> page = all.stream()
                .skip((long) pageNumber * size)
                .limit(size)
                .toList();
        return new ListingData<>(new Page<>(searchText, size, pageNumber, all.size(), page));
    }

    static boolean stateFlag(HttpRequest httpRequest, String name) {
        if (httpRequest == null || httpRequest.runActionRq() == null
                || httpRequest.runActionRq().componentState() == null) {
            return false;
        }
        return Boolean.TRUE.equals(httpRequest.runActionRq().componentState().get(name));
    }

    private static Function<Process, ProcessRow> mapProcessToRow(DateTimeFormatter dtf) {
        return process -> new ProcessRow(process.id(),
                process.getName(),
                mapProcessStatus(process.getStatus(), process.getCompletionPercentage()),
                process.getCreated() != null ? process.getCreated().format(dtf) : null,
                process.getStarted() != null ? process.getStarted().format(dtf) : null,
                process.getFinished() != null ? process.getFinished().format(dtf) : null);
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
