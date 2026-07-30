package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.country;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryDto;
import io.mateu.workflow.controlplaneservice.application.usecases.country.create.CreateCountryCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.country.create.CreateCountryUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.country.update.UpdateCountryCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.country.update.UpdateCountryUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.TierIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.TierIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class CountryViewModel implements Identifiable {
    @EditableOnlyWhenCreating
    String code;
    @NotEmpty
    String name;
    @Lookup(search = TierIdOptionsSupplier.class, label = TierIdLabelSupplier.class)
    String tier;

    final CreateCountryUseCase createCountryUseCase;
    final UpdateCountryUseCase updateCountryUseCase;

    public String create(HttpRequest httpRequest) {
        return createCountryUseCase.handle(new CreateCountryCommand(code, name, tier));
    }

    public void save(HttpRequest httpRequest) {
        updateCountryUseCase.handle(new UpdateCountryCommand(code, name, tier));
    }

    @Override
    public String id() {
        return code;
    }

    public CountryViewModel load(CountryDto country) {
        code = country.code();
        name = country.name();
        tier = country.tierId();
        return this;
    }

    @Override
    public String toString() {
        return code != null ? name : "New country";
    }
}
