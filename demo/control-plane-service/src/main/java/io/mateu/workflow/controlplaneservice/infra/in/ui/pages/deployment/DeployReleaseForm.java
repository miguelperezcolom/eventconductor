package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment;

import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.*;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.ReleaseIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.ReleaseIdOptionsSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Service
@Scope("prototype")
@Slf4j
@RequiredArgsConstructor
@Title("Deploy release")
@FormLayout(columns = 1)
@Style("max-width:900px;margin: auto;")
public class DeployReleaseForm {

    final AskForDeploymentUseCase useCase;

    @Lookup(search = ReleaseIdOptionsSupplier.class, label = ReleaseIdLabelSupplier.class)
    String release;

    @ReadOnly
    List<DeploymentRow> routes;

    @Toolbar
    public Object deploy() {
        var businessKey = UUID.randomUUID().toString();
        var command = new AskForDeploymentCommand(businessKey, routes.stream().map(DeploymentRow::id).toList(), release);
        useCase.handle(command);
        return URI.create("/workflow/processes/" + businessKey + "?returnTo=/controlPlane/releases/deployer");
    }

    public DeployReleaseForm withRoutes(List<DeploymentRow> routeIds) {
        this.routes = routeIds;
        return this;
    }
}
