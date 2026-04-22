package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoCreationForm;
import io.mateu.uidl.data.NoEditor;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.infra.in.ui.pages.process.ProcessRow;
import io.mateu.workflow.infra.in.ui.pages.process.SimpleProcessViewModel;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Error;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Message;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resource;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;
import io.mateu.workflow.infra.out.persistence.LogMessageEntity;
import io.mateu.workflow.infra.out.persistence.LogMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.ResourceEntityRepository;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import static io.mateu.uidl.Humanizer.toUpperCaseFirst;

@Service
@RequiredArgsConstructor
public class SimpleProcessCrudAdapter implements CrudAdapter<SimpleProcessViewModel, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> {

    final ProcessRepository repository;
    final StepExecutionEntityRepository stepExecutionEntityRepository;
    final LogMessageEntityRepository logMessageEntityRepository;
    final ResourceEntityRepository resourceEntityRepository;


    @Override
    public ListingData<ProcessRow> search(String searchText, NoFilters noFilters, Pageable pageable, HttpRequest httpRequest) {
        var dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return ListingData.of(repository.findAll().stream()
                        .filter(process -> searchText == null || searchText.isEmpty() ||
                                process.searchableText().toLowerCase().contains(searchText.toLowerCase()))
                .map(process -> new ProcessRow(process.id(),
                        process.getName(),
                        map(process.getStatus(), process.getCompletionPercentage()),
                        process.getCreated() != null? process.getCreated().format(dtf):null,
                        process.getStarted() != null? process.getStarted().format(dtf):null,
                        process.getFinished() != null? process.getFinished().format(dtf):null))
                        .sorted(Comparator.comparing(ProcessRow::created).reversed())
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
        return new Status(statusType, toUpperCaseFirst(status.name()) + " (" + completionPercentage + "%)");
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SimpleProcessViewModel getView(String id, HttpRequest httpRequest) {
        Process process = repository.findById(id).orElse(repository.findByBusinessKey(id).orElse(null));
        httpRequest.setAttribute("_process", process);
        httpRequest.setAttribute("_status", process.getStatus().name());
        httpRequest.setAttribute("_returnTo", httpRequest.getParameterValue("returnTo"));
        return new SimpleProcessViewModel(process.id(), process.getName(), map(process.getStatus(), process.getCompletionPercentage()),
                stepExecutionEntityRepository.findAllByProcessIdOrderByOrder(id).stream()
                        .map(entity -> new Step(id, entity.getId(), entity.getStepId(), mapStepStatus(entity.getStatus())))
                        .toList(),
                logMessageEntityRepository.findAllByProcessId(id).stream()
                        .filter(entity -> !"error".equals(entity.getMessageType()))
                        .sorted(Comparator.comparing(LogMessageEntity::getTimestamp).reversed())
                        .limit(10)
                        .map(entity -> new Message(id, entity.getId(), entity.getTimestamp(), entity.getMessage()))
                        .toList(),
                logMessageEntityRepository.findAllByProcessId(id).stream()
                        .filter(entity -> "error".equals(entity.getMessageType()))
                        .sorted(Comparator.comparing(LogMessageEntity::getTimestamp).reversed())
                        .limit(10)
                        .map(entity -> new Error(id, entity.getId(), entity.getTimestamp(), entity.getMessage()))
                        .toList(),
                resourceEntityRepository.findAllByProcessId(id).stream()
                        .map(entity -> new Resource(id, entity.getId(), entity.getName(), entity.getUrl()))
                        .toList(),
                process.getVariables().stream().map(variable -> new Variable(variable.name(), variable.value())).toList(),
                httpRequest.getParameterValue("returnTo")
                );
    }

    private Status mapStepStatus(String rawStatus) {
        StepExecutionStatus status = StepExecutionStatus.valueOf(rawStatus);
        StatusType statusType = switch (status) {
            case CREATED -> StatusType.NONE;
            case PENDING -> StatusType.INFO;
            case RUNNING -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case CANCELLED -> StatusType.DANGER;
            case ERROR -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()));
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
