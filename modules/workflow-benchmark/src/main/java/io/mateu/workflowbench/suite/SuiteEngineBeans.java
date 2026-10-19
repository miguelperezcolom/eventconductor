package io.mateu.workflowbench.suite;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Beans every engine the suite boots shares: a meter registry (what Actuator would give a real
 * deployment, so the engine's own metrics cost what they cost in production) and, in embedded mode,
 * the no-op worker.
 *
 * <p>Imported by the application classes, never component-scanned: it lives outside
 * {@code io.mateu.workflow} and outside every application's own package on purpose.
 */
@Configuration(proxyBeanMethods = false)
public class SuiteEngineBeans {

    @Bean
    MeterRegistry suiteMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * The no-op worker for embedded mode: answers every task at once, on the thread it is called on
     * (the engine default, {@code workflow.embedded.worker-threads=0}). A step whose id starts with
     * {@code fail} is reported as failed; the step declares {@code retries: 0}, so that is how the saga
     * scenario rolls back.
     */
    @Bean
    @ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
    EmbeddedTaskExecutor suiteNoopWorker(UpdateStepExecutionUseCase updateStepExecution) {
        return request -> {
            var fails = request.stepId() != null && request.stepId().startsWith("fail");
            updateStepExecution.handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(), List.of(), "",
                    fails ? StepExecutionStatus.ERROR : StepExecutionStatus.COMPLETED));
        };
    }
}
