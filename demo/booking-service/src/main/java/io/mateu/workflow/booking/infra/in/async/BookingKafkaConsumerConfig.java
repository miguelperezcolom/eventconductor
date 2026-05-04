package io.mateu.workflow.booking.infra.in.async;

import io.mateu.workflow.booking.application.usecases.booking.changestatus.ChangeBookingStatusCommand;
import io.mateu.workflow.booking.application.usecases.booking.changestatus.ChangeBookingStatusUseCase;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class BookingKafkaConsumerConfig {

    final ChangeBookingStatusUseCase changeBookingStatusUseCase;
    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent() {
        return event -> {
            log.info("Received event: " + event);
            if (event instanceof TaskExecutionRequested taskExecutionRequested) {

                if (taskExecutionRequested.stepId().equals("cambiar-estado-reserva")) {
                    new Thread(() -> changeBookingStatusUseCase
                            .handle(new ChangeBookingStatusCommand(taskExecutionRequested.variables().stream()
                                    .filter(variable -> "bookingId".equals(variable.name()))
                                    .findAny().orElseThrow().value(),
                                    BookingStatus.valueOf(taskExecutionRequested.variables().stream()
                                            .filter(variable -> "status".equals(variable.name()))
                                            .findAny().orElseThrow().value()),
                                    taskExecutionRequested.taskExecutionId()))).start();
                }

            }


        };
    }

}
