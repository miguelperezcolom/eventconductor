package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoListAdapter;
import io.mateu.uidl.data.Data;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoCreationForm;
import io.mateu.uidl.data.NoEditor;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.data.State;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessRow;
import io.mateu.workflow.infra.in.ui.pages.process.SimpleProcessViewModel;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.NonNull;
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
public class SimpleProcessCrudAdapter extends AutoListAdapter<ProcessRow> {

    final SimpleProcessViewModel model;
    final ProcessRepository repository;
    final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public ListingData<ProcessRow> search(String searchText, ProcessRow filters, Pageable pageable, HttpRequest httpRequest) {
        return ListingData.of(repository.findAll().stream()
                        .filter(process -> searchText == null || searchText.isEmpty() ||
                                process.searchableText().toLowerCase().contains(searchText.toLowerCase()))
                .map(mapProcessToRow(dtf))
                        .sorted(Comparator.comparing(ProcessRow::created).reversed())
                .toList());
    }

    private static @NonNull Function<Process, ProcessRow> mapProcessToRow(DateTimeFormatter dtf) {
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
            case RUNNING -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case CANCELLED -> StatusType.NONE;
            case ERROR -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()) + " (" + completionPercentage + "%)");
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
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

    @Override
    public CrudRepository<ProcessRow> repository() {
        return new CrudRepository<ProcessRow>() {
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
