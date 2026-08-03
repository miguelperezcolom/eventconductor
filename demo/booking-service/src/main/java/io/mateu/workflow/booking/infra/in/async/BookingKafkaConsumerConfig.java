package io.mateu.workflow.booking.infra.in.async;

import io.mateu.workflow.booking.application.usecases.booking.changestatus.ChangeBookingStatusCommand;
import io.mateu.workflow.booking.application.usecases.booking.changestatus.ChangeBookingStatusUseCase;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.WorkerReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * The booking worker for the saga demo: it confirms or cancels a booking on request and answers
 * the engine through {@link WorkerReply}.
 *
 * <p>The task is handled on the consumer thread, deliberately. Handing it to a thread of its own
 * — which this used to do — commits the offset immediately, so a reply the broker will not take
 * has nothing left to redeliver and the step waits forever.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class BookingKafkaConsumerConfig {

    final ChangeBookingStatusUseCase changeBookingStatusUseCase;

    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent() {
        return event -> {
            log.info("Received event: " + event);
            if (!(event instanceof TaskExecutionRequested task)) {
                return;
            }

            switch (task.stepId()) {
                case "confirmar-reserva" -> changeStatus(task, BookingStatus.Confirmed);
                case "cancelar-reserva" -> changeStatus(task, BookingStatus.Cancelled);
                default -> log.debug("No handler for step {}", task.stepId());
            }
        };
    }

    private void changeStatus(TaskExecutionRequested task, BookingStatus status) {
        changeBookingStatusUseCase.handle(new ChangeBookingStatusCommand(
                bookingId(task), status, task.taskExecutionId(), task.processId()));
    }

    private String bookingId(TaskExecutionRequested task) {
        return task.variables().stream()
                .filter(variable -> "bookingId".equals(variable.name()))
                .findAny()
                .map(Variable::value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Step " + task.stepId() + " needs a 'bookingId' variable"));
    }

}
