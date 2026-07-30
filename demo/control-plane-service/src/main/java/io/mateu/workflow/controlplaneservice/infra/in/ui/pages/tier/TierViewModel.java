package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.tier;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.TierDto;
import io.mateu.workflow.controlplaneservice.application.usecases.tier.create.CreateTierCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.tier.create.CreateTierUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.tier.update.UpdateTierCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.tier.update.UpdateTierUseCase;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class TierViewModel implements Identifiable {
    @EditableOnlyWhenCreating
    String code;
    @NotEmpty
    String name;
    @Min(1)
    int parallelThreads;

    final CreateTierUseCase createTierUseCase;
    final UpdateTierUseCase updateTierUseCase;

    public String create(HttpRequest httpRequest) {
        return createTierUseCase.handle(new CreateTierCommand(code, name, parallelThreads));
    }

    public void save(HttpRequest httpRequest) {
        updateTierUseCase.handle(new UpdateTierCommand(code, name, parallelThreads));
    }

    @Override
    public String id() {
        return code;
    }

    public TierViewModel load(TierDto tier) {
        code = tier.id();
        name = tier.name();
        parallelThreads = tier.parallelThreads();
        return this;
    }

    @Override
    public String toString() {
        return code != null ? name : "New tier";
    }
}
