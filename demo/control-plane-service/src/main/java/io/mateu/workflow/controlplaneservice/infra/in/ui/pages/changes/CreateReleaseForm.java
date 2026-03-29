package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes;

import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.ForeignKey;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Hydratable;
import io.mateu.uidl.interfaces.Page;
import io.mateu.workflow.controlplaneservice.application.usecases.createrelease.CreateReleaseCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.createrelease.CreateReleaseUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.EnvironmentIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.EnvironmentIdOptionsSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.SiteIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.SiteIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.With;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Base64;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.fromJson;

@Service
@RequiredArgsConstructor
@Title("Create release")
@Style("max-width:900px;margin: auto;")
public class CreateReleaseForm {

    final CreateReleaseUseCase useCase;

    @ReadOnly
    String user;
    @NotEmpty
    String name;
    @ForeignKey(search = EnvironmentIdOptionsSupplier.class, label = EnvironmentIdLabelSupplier.class)
            @NotNull
    String environment;
    @ForeignKey(search = SiteIdOptionsSupplier.class, label = SiteIdLabelSupplier.class)
    @NotNull
    String site;

    @Toolbar
    URI create() {
        useCase.handle(new CreateReleaseCommand(name, site, user, environment));
        return URI.create("/controlPlane/releases");
    }

    public CreateReleaseForm withUser(String user) {
        this.user = user;
        return this;
    }
}
