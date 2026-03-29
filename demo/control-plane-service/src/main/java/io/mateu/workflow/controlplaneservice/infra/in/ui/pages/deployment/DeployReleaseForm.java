package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment;

import io.mateu.uidl.annotations.ForeignKey;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployUseCase;
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

    @ForeignKey(search = ReleaseIdOptionsSupplier.class, label = ReleaseIdLabelSupplier.class)
    String release;

    @ReadOnly
    List<String> routeIds;

    @Toolbar
    public Object deploy() {
        deploymentProcessViewModel.reset();
        new Thread(() -> useCase.handle(new DeployCommand(List.of(), release))).start();
        return deploymentProcessViewModel;
    }

    public DeployReleaseForm withRouteIds(List<String> routeIds) {
        this.routeIds = routeIds;
        return this;
    }
}
