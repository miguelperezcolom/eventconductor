package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site;


import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Tab;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.State;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.fluent.TriggersSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Error;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Message;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Resource;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@ReadOnly
@Service
@RequiredArgsConstructor
@Style("max-width:900px;margin: auto;")
public class ScrapeProcessViewModel implements TriggersSupplier {

    Status status = new Status(StatusType.INFO, "Pending");

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

    @Override
    public String toString() {
        return "Download Riu.com";
    }

    @SneakyThrows
    public Object refresh() {
        SiteCrudOrchestrator siteCrudOrchestrator = MateuBeanProvider.getBean(SiteCrudOrchestrator.class);
        steps = siteCrudOrchestrator.steps;
        messages = siteCrudOrchestrator.messages;
        errors = siteCrudOrchestrator.errors;
        resources = siteCrudOrchestrator.resources;

        status = new Status(StatusType.WARNING, "Running");
        if (steps.size() > 0 && steps.stream()
                .filter(step -> !step.status().type().equals(StatusType.SUCCESS)).toList().size() == 0)
            status = new Status(StatusType.SUCCESS, "Completed");
        if (steps.size() > 0 && steps.stream()
                .filter(step -> step.status().type().equals(StatusType.DANGER)).toList().size() > 0)
            status = new Status(StatusType.DANGER, "Error");
        if (status.type().equals(StatusType.SUCCESS)) {
            return new URI("/controlPlane/routes");
        }
        return new State(this);
    }

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<Trigger>();
        triggers.add(new OnLoadTrigger("refresh", 1000, 1, "state.status.type != 'SUCCESS' && state.status.type != 'DANGER'"));
        triggers.add(new OnSuccessTrigger("refresh", "refresh", "state.status.type != 'SUCCESS' && state.status.type != 'DANGER'", 1000));
        return triggers;
    }

    public void reset() {
        steps = new ArrayList<>();
        messages = new ArrayList<>();
        errors = new ArrayList<>();
        resources = new ArrayList<>();
        status = new Status(StatusType.INFO, "Pending");
    }
}
