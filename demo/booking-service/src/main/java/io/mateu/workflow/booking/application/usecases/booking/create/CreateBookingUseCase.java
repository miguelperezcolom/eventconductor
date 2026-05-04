package io.mateu.workflow.booking.application.usecases.booking.create;

import io.mateu.workflow.booking.application.out.repository.BookingRepository;
import io.mateu.workflow.booking.domain.aggregates.booking.Booking;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingId;
import io.mateu.workflow.booking.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateBookingUseCase {

    final BookingRepository repository;

    @Transactional
    public String handle(CreateBookingCommand command) {
        return repository.save(Booking.of(
                new BookingId(UUID.randomUUID().toString()),
                new Name(command.leadName())
        )).id();
    }

}
