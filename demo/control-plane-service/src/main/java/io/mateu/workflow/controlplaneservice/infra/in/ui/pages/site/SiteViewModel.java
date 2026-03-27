package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.SiteDto;
import io.mateu.workflow.controlplaneservice.application.usecases.site.create.CreateSiteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.create.CreateSiteUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.site.update.UpdateSiteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.update.UpdateSiteUseCase;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class SiteViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @EditableOnlyWhenCreating
    String id;
    @NotEmpty
    String name;
    @NotEmpty
    String url;

    final CreateSiteUseCase createSiteUseCase;
    final UpdateSiteUseCase updateSiteUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        return createSiteUseCase.handle(new CreateSiteCommand(id, name, url));
    }

    @Override
    public void save(HttpRequest httpRequest) {
        updateSiteUseCase.handle(new UpdateSiteCommand(id, name, url));
    }

    @Override
    public String id() {
        return id;
    }

    public SiteViewModel load(SiteDto site) {
        id = String.valueOf(site.id());
        name = site.name();
        url = site.url();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New site";
    }
}
