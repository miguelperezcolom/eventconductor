package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes;

import io.mateu.uidl.annotations.*;
import io.mateu.workflow.controlplaneservice.application.usecases.changes.createrelease.AskForReleaseCreationCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.changes.createrelease.AskForReleaseCreationUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.SiteIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.SiteIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Title("Create release")
@Style("max-width:900px;margin: auto;")
public class CreateReleaseForm {

    final AskForReleaseCreationUseCase useCase;

    @ReadOnly
    String user;
    @Lookup(search = SiteIdOptionsSupplier.class, label = SiteIdLabelSupplier.class)
    @NotNull
    String site;
    @NotEmpty
    String name;

    @Toolbar
    @Action(validationRequired = true)
    Object create() {
        var businessKey = UUID.randomUUID().toString();
        useCase.handle(new AskForReleaseCreationCommand(businessKey, name, site, user));
        return URI.create("/workflow/processes/" + businessKey + "?returnTo=/controlPlane/releases/releases");
    }

    public CreateReleaseForm withUser(String user) {
        this.user = user;
        return this;
    }
}
