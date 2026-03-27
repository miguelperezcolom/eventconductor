package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.country;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryDto;
import io.mateu.workflow.controlplaneservice.application.usecases.country.create.CreateCountryCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.country.create.CreateCountryUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.country.update.UpdateCountryCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.country.update.UpdateCountryUseCase;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class CountryViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @EditableOnlyWhenCreating
    String code;
    @NotEmpty
    String name;

    final CreateCountryUseCase createCountryUseCase;
    final UpdateCountryUseCase updateCountryUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        return createCountryUseCase.handle(new CreateCountryCommand(code, name));
    }

    @Override
    public void save(HttpRequest httpRequest) {
        updateCountryUseCase.handle(new UpdateCountryCommand(code, name));
    }

    @Override
    public String id() {
        return code;
    }

    public CountryViewModel load(CountryDto country) {
        code = country.code();
        name = country.name();
        return this;
    }

    @Override
    public String toString() {
        return code != null ? name : "New country";
    }
}
