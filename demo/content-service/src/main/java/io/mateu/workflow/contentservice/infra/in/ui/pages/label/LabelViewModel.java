package io.mateu.workflow.contentservice.infra.in.ui.pages.label;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.contentservice.application.query.dto.LabelDto;
import io.mateu.workflow.contentservice.application.usecases.label.create.CreateLabelCommand;
import io.mateu.workflow.contentservice.application.usecases.label.create.CreateLabelUseCase;
import io.mateu.workflow.contentservice.application.usecases.label.update.UpdateLabelCommand;
import io.mateu.workflow.contentservice.application.usecases.label.update.UpdateLabelUseCase;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class LabelViewModel implements Identifiable {
    @HiddenInCreate
    @ReadOnly
    String id;
    @NotEmpty String name;

    final CreateLabelUseCase createLabelUseCase;
    final UpdateLabelUseCase updateLabelUseCase;

    public String create(HttpRequest httpRequest) {
        return createLabelUseCase.handle(new CreateLabelCommand(name));
    }

    public void save(HttpRequest httpRequest) {
        updateLabelUseCase.handle(new UpdateLabelCommand(id, name));
    }

    @Override
    public String id() {
        return id;
    }

    public LabelViewModel load(LabelDto label) {
        id = String.valueOf(label.id());
        name = label.name();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New label";
    }
}
