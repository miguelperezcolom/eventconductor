package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.language;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.LanguageDto;
import io.mateu.workflow.controlplaneservice.application.usecases.language.create.CreateLanguageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.language.create.CreateLanguageUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.language.update.UpdateLanguageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.language.update.UpdateLanguageUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.Language;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class LanguageViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;

        final CreateLanguageUseCase createLanguageUseCase;
        final UpdateLanguageUseCase updateLanguageUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createLanguageUseCase.handle(new CreateLanguageCommand(name));
        }

        @Override
        public void save(HttpRequest httpRequest) {
        updateLanguageUseCase.handle(new UpdateLanguageCommand(id, name));
        }

        @Override
        public String id() {
        return id;
        }

        public LanguageViewModel load(LanguageDto language) {
        id = String.valueOf(language.id());
        name = language.name();
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New language";
        }
        }
