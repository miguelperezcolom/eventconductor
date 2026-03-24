package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.SiteDto;
import io.mateu.workflow.controlplaneservice.application.usecases.site.create.CreateSiteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.create.CreateSiteUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.site.update.UpdateSiteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.update.UpdateSiteUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.Site;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class SiteViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;

        final CreateSiteUseCase createSiteUseCase;
        final UpdateSiteUseCase updateSiteUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createSiteUseCase.handle(new CreateSiteCommand(name));
        }

        @Override
        public void save(HttpRequest httpRequest) {
        updateSiteUseCase.handle(new UpdateSiteCommand(id, name));
        }

        @Override
        public String id() {
        return id;
        }

        public SiteViewModel load(SiteDto site) {
        id = String.valueOf(site.id());
        name = site.name();
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New site";
        }
        }
