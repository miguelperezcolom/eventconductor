package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessRow;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessViewModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.mateu.core.domain.Humanizer.toUpperCaseFirst;

@Service
@RequiredArgsConstructor
public class ProcessCrudAdapter implements CrudAdapter<ProcessViewModel, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> {

    final ProcessRepository repository;

    @Override
    public ListingData<ProcessRow> search(String searchText, NoFilters noFilters, Pageable pageable) {
        var dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return ListingData.of(repository.findAll().stream()
                        .filter(process -> searchText == null || searchText.isEmpty() ||
                                process.searchableText().toLowerCase().contains(searchText.toLowerCase()))
                .map(process -> new ProcessRow(process.id(),
                        process.getName(),
                        map(process.getStatus(), process.getCompletionPercentage()),
                        process.getCreated() != null?process.getCreated().format(dtf):null,
                        process.getStarted() != null?process.getStarted().format(dtf):null,
                        process.getFinished() != null?process.getFinished().format(dtf):null))
                .toList());
    }

    private Status map(ProcessStatus status, int completionPercentage) {
        StatusType statusType = switch (status) {
            case PENDING -> StatusType.INFO;
            case RUNNING -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case CANCELLED -> StatusType.NONE;
            case ERROR -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name() + " (" + completionPercentage + "%)"));
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProcessViewModel getView(String id) {
        Process process = repository.findById(id).orElse(null);
        return new ProcessViewModel(process.id(), process.getName(), map(process.getStatus(), process.getCompletionPercentage()));
    }

    @Override
    public NoEditor<String> getEditor(String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public NoCreationForm<String> getCreationForm(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }
}
