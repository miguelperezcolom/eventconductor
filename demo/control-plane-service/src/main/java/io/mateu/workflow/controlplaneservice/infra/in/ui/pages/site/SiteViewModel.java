package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.FieldStereotype;
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
public class SiteViewModel implements Identifiable {
    @EditableOnlyWhenCreating
    String id;
    @NotEmpty
    String name;
    @NotEmpty
    String url;
    @NotEmpty
            @Stereotype(FieldStereotype.textarea)
    String llmsTxt;

    final CreateSiteUseCase createSiteUseCase;
    final UpdateSiteUseCase updateSiteUseCase;

    public String create(HttpRequest httpRequest) {
        return createSiteUseCase.handle(new CreateSiteCommand(id, name, url, llmsTxt));
    }

    public void save(HttpRequest httpRequest) {
        updateSiteUseCase.handle(new UpdateSiteCommand(id, name, url, llmsTxt));
    }

    @Override
    public String id() {
        return id;
    }

    public SiteViewModel load(SiteDto site) {
        id = String.valueOf(site.id());
        name = site.name();
        url = site.url();
        llmsTxt = site.llmsTxt();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New site";
    }

}
