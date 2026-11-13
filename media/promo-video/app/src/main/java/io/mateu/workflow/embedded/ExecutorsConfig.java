package io.mateu.workflow.embedded;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The promo video's hotel: every room books, except that the hotel refuses to confirm room 102 —
 * so the booking rolls back and the console has a failed lane and two compensations to show.
 * Answers arrive after a short delay, like a real system's would.
 */
@ConditionalOnProperty(name = "promo.app", havingValue = "true", matchIfMissing = true)
@Configuration
public class ExecutorsConfig {

    private final ScheduledExecutorService hotel = Executors.newScheduledThreadPool(4);

    @Bean
    public EmbeddedTaskExecutor hotelWorker(UpdateStepExecutionUseCase updates) {
        return request -> hotel.schedule(() -> answer(updates, request),
                Long.getLong("promo.worker-delay-ms", 1200), TimeUnit.MILLISECONDS);
    }

    private void answer(UpdateStepExecutionUseCase updates, TaskExecutionRequested request) {
        String room = variable(request, "room");
        if ("confirm".equals(request.stepId()) && room != null && room.contains("102")) {
            updates.handle(new UpdateStepExecutionCommand(request.taskExecutionId(), List.of(),
                    "The hotel refused to confirm room 102 (overbooked)", StepExecutionStatus.ERROR));
            return;
        }
        List<Variable> out = "create".equals(request.stepId())
                ? List.of(new Variable("roomBookingId", "HB-" + Math.abs((room + request.processId()).hashCode() % 100000)))
                : List.of();
        updates.handle(new UpdateStepExecutionCommand(request.taskExecutionId(), out, "", StepExecutionStatus.COMPLETED));
    }

    private static String variable(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(v -> v.value()).findFirst().orElse(null);
    }
}
