package io.mateu.workflow.booking.application.usecases.booking.changestatus;

import io.mateu.workflow.booking.application.out.repository.BookingRepository;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingId;
import io.mateu.workflow.booking.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChangeBookingStatusUseCase {

    final BookingRepository repository;

    @Transactional
    public void handle(ChangeBookingStatusCommand command) {
        var resource = repository.findById(new BookingId(command.id())).orElseThrow();
        resource.changeStatus(command.status());
        repository.save(resource);
    }

}
