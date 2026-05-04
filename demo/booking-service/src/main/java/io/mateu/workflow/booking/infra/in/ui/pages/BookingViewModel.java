package io.mateu.workflow.booking.infra.in.ui.pages;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.booking.application.out.query.dto.BookingDto;
import io.mateu.workflow.booking.application.usecases.booking.create.CreateBookingCommand;
import io.mateu.workflow.booking.application.usecases.booking.create.CreateBookingUseCase;
import io.mateu.workflow.booking.application.usecases.booking.update.UpdateBookingCommand;
import io.mateu.workflow.booking.application.usecases.booking.update.UpdateBookingUseCase;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class BookingViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @HiddenInCreate
    @ReadOnly
    String id;
    @NotEmpty
    String leadName;
    @ReadOnly
    LocalDateTime created;
    @ReadOnly
    BookingStatus status;

    final CreateBookingUseCase createResourceUseCase;
    final UpdateBookingUseCase updateResourceUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        return createResourceUseCase.handle(new CreateBookingCommand(UUID.randomUUID().toString(), leadName));
    }

    @Override
    public void save(HttpRequest httpRequest) {
        updateResourceUseCase.handle(new UpdateBookingCommand(id, leadName));
    }

    @Override
    public String id() {
        return id;
    }

    public BookingViewModel load(BookingDto resource) {
        id = String.valueOf(resource.id());
        leadName = resource.leadName();
        created = resource.created();
        status = resource.status();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? leadName : "New booking";
    }
}
