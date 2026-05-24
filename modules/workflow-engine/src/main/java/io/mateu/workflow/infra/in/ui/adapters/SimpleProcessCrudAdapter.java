package io.mateu.workflow.infra.in.ui.adapters;

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
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessRow;
import io.mateu.workflow.infra.in.ui.pages.process.SimpleProcessViewModel;
import io.mateu.workflow.infra.out.persistence.LogMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.ResourceEntityRepository;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static io.mateu.uidl.Humanizer.toUpperCaseFirst;

@Service
@RequiredArgsConstructor
public class SimpleProcessCrudAdapter implements CrudAdapter<Object, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> {

    final SimpleProcessViewModel model;
    final ProcessRepository repository;

    @Override
    public ListingData<ProcessRow> search(String searchText, NoFilters noFilters, Pageable pageable, HttpRequest httpRequest) {
        var dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return ListingData.of(repository.findAll().stream()
                        .filter(process -> searchText == null || searchText.isEmpty() ||
                                process.searchableText().toLowerCase().contains(searchText.toLowerCase()))
                .map(process -> new ProcessRow(process.id(),
                        process.getName(),
                        mapProcessStatus(process.getStatus(), process.getCompletionPercentage()),
                        process.getCreated() != null? process.getCreated().format(dtf):null,
                        process.getStarted() != null? process.getStarted().format(dtf):null,
                        process.getFinished() != null? process.getFinished().format(dtf):null))
                        .sorted(Comparator.comparing(ProcessRow::created).reversed())
                .toList());
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
    public NoEditor<String> getEditor(String id, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public NoCreationForm<String> getCreationForm(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }
}
