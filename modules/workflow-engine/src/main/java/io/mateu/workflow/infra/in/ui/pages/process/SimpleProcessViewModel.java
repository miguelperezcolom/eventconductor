package io.mateu.workflow.infra.in.ui.pages.process;


import io.mateu.uidl.annotations.*;
import io.mateu.uidl.annotations.Tab;
import io.mateu.uidl.data.*;
import io.mateu.uidl.data.State;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.fluent.TriggersSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ResourceRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.With;
import org.springframework.context.annotation.Scope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
public class SimpleProcessViewModel implements TriggersSupplier, VisibilitySupplier {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final LogMessageRepository logMessageRepository;
    final ResourceRepository resourceRepository;
    final CancelProcessUseCase cancelProcessUseCase;
    final RetryProcessUseCase retryProcessUseCase;


    String id;

    String name;

    Status status;

    @ReadOnly
    String returnTo;

    // Explicit tab names: since mateu 379440d83 consecutive @Tab annotations with the SAME
    // value (and bare @Tab means value "") are merged into one tab, which stacked these four
    // lists vertically. Distinct values keep one tab per list.
    @Tab("Steps")
    @Label("")
    List<Step> steps;

    @Tab("Messages")
    @Label("")
    List<Message> messages;

    @Tab("Errors")
    @Label("")
    List<Error> errors;

    @Tab("Resources")
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
        this.steps = stepExecutionRepository.findByProcess(process).stream()
                .map(se -> new Step(id, se.id(), se.getStepId(), mapStepStatus(se.getStatus().name())))
                .toList();
        this.messages = logMessageRepository.findByProcessId(id).stream()
                .filter(msg -> !"error".equals(msg.getMessageType()))
                .sorted(Comparator.comparing(LogMessage::getTimestamp).reversed())
                .limit(10)
                .map(msg -> new Message(id, msg.id(), msg.getTimestamp(), msg.getMessage()))
                .toList();
        this.errors = logMessageRepository.findByProcessId(id).stream()
                .filter(msg -> "error".equals(msg.getMessageType()))
                .sorted(Comparator.comparing(LogMessage::getTimestamp).reversed())
                .limit(10)
                .map(msg -> new Error(id, msg.id(), msg.getTimestamp(), msg.getMessage()))
                .toList();
        this.resources = resourceRepository.findByProcessId(id).stream()
                .map(r -> new Resource(id, r.id(), r.getName(), r.getUrl()))
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
        return "Process " + (id != null && id.length() > 5?"..." + id.substring(id.length() - 5):id);
    }

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<Trigger>();
        triggers.add(new OnLoadTrigger("refresh", 2000, 1, "state.status.type != 'SUCCESS'"));
        triggers.add(new OnSuccessTrigger("refresh", "refresh", "state.status.type != 'SUCCESS'", 2000));
        return triggers;
    }

    @Toolbar(buttonStyle = ButtonStyle.secondary, buttonColor = ButtonColor.error)
    @Action(confirmationRequired = true)
    //@Hidden("state.status.type == 'SUCCESS' || state.status.type == 'DANGER'")
    public void cancelProcess() {
        cancelProcessUseCase.handle(new CancelProcessCommand(id));
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
            case CREATED -> StatusType.NONE;
            case PENDING -> StatusType.INFO;
            case RUNNING -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case ERROR, TIMEOUT, CANCELLED -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()));
    }

    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        if ("cancelProcess".equals(memberName)) {
            return StatusType.SUCCESS.equals(status.type()) || StatusType.DANGER.equals(status.type());
        }
        return false;
    }
}
