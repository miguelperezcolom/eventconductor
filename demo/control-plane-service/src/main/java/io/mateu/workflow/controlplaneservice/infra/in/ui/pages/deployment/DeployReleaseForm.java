package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment;

import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.SetPlannedReleaseUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.ReleaseIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.ReleaseIdOptionsSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
@Title("Deploy release")
@FormLayout(columns = 1)
@Style("max-width:900px;margin: auto;")
public class DeployReleaseForm {

    final DeploymentProcessViewModel deploymentProcessViewModel;
    final DeployUseCase useCase;
    final SetPlannedReleaseUseCase setPlannedReleaseUseCase;

    @Lookup(search = ReleaseIdOptionsSupplier.class, label = ReleaseIdLabelSupplier.class)
    String release;

    @ReadOnly
    List<DeploymentRow> routes;

    @Toolbar
    public Object deploy() {
        var command = new DeployCommand(routes.stream().map(DeploymentRow::id).toList(), release);
        setPlannedReleaseUseCase.handle(command);
        deploymentProcessViewModel.reset();
        new Thread(() -> useCase.handle(command)).start();
        return deploymentProcessViewModel;
    }

    public DeployReleaseForm withRoutes(List<DeploymentRow> routeIds) {
        this.routes = routeIds;
        return this;
    }
}
