package io.mateu.workflow.booking.application.usecases.booking.changestatus;

import io.mateu.workflow.booking.application.out.repository.BookingRepository;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingId;
import io.mateu.workflow.booking.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChangeBookingStatusUseCase {

    final BookingRepository repository;
    final StreamBridge streamBridge;

    @Transactional
    public void handle(ChangeBookingStatusCommand command) {
        var resource = repository.findById(new BookingId(command.id())).orElseThrow();
        resource.changeStatus(command.status());
        repository.save(resource);

        streamBridge.send("upstream", new TaskStatusChanged(
                command.taskExecutionId(),
                TaskStatus.COMPLETED,
                List.of()));
    }

}
