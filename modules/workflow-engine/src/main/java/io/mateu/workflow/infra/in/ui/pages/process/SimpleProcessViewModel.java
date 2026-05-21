package io.mateu.workflow.infra.in.ui.pages.process;


import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.State;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.fluent.TriggersSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Errors;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Message;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Messages;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resource;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resources;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Steps;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Error;
import io.mateu.workflow.infra.out.persistence.LogMessageEntity;
import io.mateu.workflow.infra.out.persistence.LogMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.ResourceEntityRepository;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.With;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

import static io.mateu.uidl.Humanizer.toUpperCaseFirst;
import static io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter.mapProcessStatus;

@RequiredArgsConstructor
@Getter
@ReadOnly
@Service
@Scope("prototype")
public class SimpleProcessViewModel implements TriggersSupplier {

    final ProcessRepository processRepository;
    final StepExecutionEntityRepository stepExecutionEntityRepository;
    final LogMessageEntityRepository logMessageEntityRepository;
    final ResourceEntityRepository resourceEntityRepository;
    final CancelProcessUseCase cancelProcessUseCase;

    String id;

    String name;

    Status status;

    @ReadOnly
    String returnTo;

    @Tab
    @Label("")
    List<Step> steps;

    @Tab
    @Label("")
    List<Message> messages;

    @Tab
    @Label("")
    List<Error> errors;

    @Tab
    @Label("")
    List<Resource> resources;

    @Tab
    @Label("")
    List<Variable> variables;

    public Object load(String id, HttpRequest httpRequest) {
        this.id = id;
        Process process = processRepository.findById(id).orElse(processRepository.findByBusinessKey(id).orElse(null));
        this.name = process.getName();
        this.status = mapProcessStatus(process.getStatus(), process.getCompletionPercentage());
        this.steps = stepExecutionEntityRepository.findAllByProcessIdOrderByOrder(id).stream()
                .map(entity -> new Step(id, entity.getId(), entity.getStepId(), mapStepStatus(entity.getStatus())))
                .toList();
        this.messages = logMessageEntityRepository.findAllByProcessId(id).stream()
                .filter(entity -> !"error".equals(entity.getMessageType()))
                .sorted(Comparator.comparing(LogMessageEntity::getTimestamp).reversed())
                .limit(10)
                .map(entity -> new Message(id, entity.getId(), entity.getTimestamp(), entity.getMessage()))
                .toList();
        this.errors = logMessageEntityRepository.findAllByProcessId(id).stream()
                .filter(entity -> "error".equals(entity.getMessageType()))
                .sorted(Comparator.comparing(LogMessageEntity::getTimestamp).reversed())
                .limit(10)
                .map(entity -> new Error(id, entity.getId(), entity.getTimestamp(), entity.getMessage()))
                .toList();
        this.resources = resourceEntityRepository.findAllByProcessId(id).stream()
                .map(entity -> new Resource(id, entity.getId(), entity.getName(), entity.getUrl()))
                .toList();
        this.variables = process.getVariables().stream().map(variable -> new Variable(variable.name(), variable.value())).toList();
        this.returnTo = httpRequest.getParameterValue("returnTo");

        if (ProcessStatus.COMPLETED.equals(process.getStatus())) {
            var returnTo = (String) httpRequest.runActionRq().componentState().get("returnTo");
            if (returnTo == null) {
                if (httpRequest.runActionRq().route().contains("returnTo")) {
                    returnTo = httpRequest.runActionRq().route().substring(httpRequest.runActionRq().route().indexOf("returnTo=") + "returnTo=".length());
                }
            }
            if (returnTo != null) {
                return URI.create(returnTo);
            }
        }
        return this;
    }

    @Override
    public String toString() {
        return "Process " + id;
    }

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<Trigger>();
        triggers.add(new OnLoadTrigger("refresh", 2000, 1, "state.status.type != 'SUCCESS'"));
        triggers.add(new OnSuccessTrigger("refresh", "refresh", "state.status.type != 'SUCCESS'", 2000));
        return triggers;
    }

    @Toolbar
    public void cancelProcess(SimpleProcessViewModel state) {
        cancelProcessUseCase.handle(new CancelProcessCommand(state.getId()));
    }


    @Action
    public Object refresh(HttpRequest httpRequest) {
        var id = (String) httpRequest.runActionRq().componentState().get("id");
        if (id != null) {
            var loaded = load(id, httpRequest);
            if (loaded instanceof URI uri) {
                return uri;
            }
            return new State(loaded);
        }
        return new State(this);
    }


    Status mapStepStatus(String rawStatus) {
        StepExecutionStatus status = StepExecutionStatus.valueOf(rawStatus);
        StatusType statusType = switch (status) {
            case CREATED, CANCELLED -> StatusType.NONE;
            case PENDING -> StatusType.INFO;
            case RUNNING -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case ERROR, TIMEOUT -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()));
    }

}
