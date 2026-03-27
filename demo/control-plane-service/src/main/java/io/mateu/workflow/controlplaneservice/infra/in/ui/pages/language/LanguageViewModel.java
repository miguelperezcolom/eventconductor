package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.language;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.LanguageDto;
import io.mateu.workflow.controlplaneservice.application.usecases.language.create.CreateLanguageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.language.create.CreateLanguageUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.language.update.UpdateLanguageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.language.update.UpdateLanguageUseCase;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class LanguageViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @EditableOnlyWhenCreating
    String code;
    @NotEmpty
    String name;

    final CreateLanguageUseCase createLanguageUseCase;
    final UpdateLanguageUseCase updateLanguageUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        return createLanguageUseCase.handle(new CreateLanguageCommand(code, name));
    }

    @Override
    public void save(HttpRequest httpRequest) {
        updateLanguageUseCase.handle(new UpdateLanguageCommand(code, name));
    }

    @Override
    public String id() {
        return code;
    }

    public LanguageViewModel load(LanguageDto language) {
        code = String.valueOf(language.code());
        name = language.name();
        return this;
    }

    @Override
    public String toString() {
        return code != null ? name : "New language";
    }
}
