package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.PageDto;
import io.mateu.workflow.controlplaneservice.application.usecases.page.create.CreatePageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.page.create.CreatePageUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.page.update.UpdatePageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.page.update.UpdatePageUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.Page;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class PageViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;

        final CreatePageUseCase createPageUseCase;
        final UpdatePageUseCase updatePageUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createPageUseCase.handle(new CreatePageCommand(name));
        }

        @Override
        public void save(HttpRequest httpRequest) {
        updatePageUseCase.handle(new UpdatePageCommand(id, name));
        }

        @Override
        public String id() {
        return id;
        }

        public PageViewModel load(PageDto page) {
        id = String.valueOf(page.id());
        name = page.name();
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New page";
        }
        }
