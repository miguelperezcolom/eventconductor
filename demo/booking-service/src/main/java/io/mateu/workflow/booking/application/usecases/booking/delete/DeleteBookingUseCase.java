package io.mateu.workflow.booking.application.usecases.booking.delete;

import io.mateu.workflow.booking.application.out.repository.BookingRepository;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteBookingUseCase {

    final BookingRepository repository;

    @Transactional
    public void handle(DeleteBookingCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(BookingId::new)
                .toList());
    }

}
