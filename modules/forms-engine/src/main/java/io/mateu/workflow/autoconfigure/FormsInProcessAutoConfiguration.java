package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.usecases.canceltask.CancelTaskUseCase;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.application.usecases.escalatetask.EscalateTaskUseCase;
import io.mateu.workflow.infra.in.inprocess.FormsInProcessTaskRoute;
import io.mateu.workflow.infra.out.inprocess.InProcessStreamOperations;
import io.mateu.workflow.inprocess.InProcessUpstream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * The forms engine next to an embedded workflow engine ({@code workflow.mode=embedded}, the default):
 * no broker, and Spring Cloud Stream switched off — so no {@code StreamBridge}, which is what the forms
 * engine publishes through in kafka mode.
 *
 * <ul>
 *   <li>{@link InProcessStreamOperations} stands in for the {@code StreamBridge}: replies go to the
 *       in-process engine, task events become Spring application events. It backs off whenever a
 *       {@code StreamBridge} (or any other {@link StreamOperations}) exists — a forms engine of its own
 *       ({@code forms-standalone-app}) keeps its broker even when it leaves {@code workflow.mode} unset.</li>
 *   <li>{@link FormsInProcessTaskRoute} takes the USER_TASKs the embedded engine dispatches, before the
 *       application's {@code EmbeddedTaskExecutor}. {@code forms.embedded.serve-user-tasks=false} leaves
 *       them to the executor — the workflow-test kit does, so {@code completeUserTask} keeps answering them.</li>
 * </ul>
 */
@AutoConfiguration(afterName = "org.springframework.cloud.stream.function.FunctionConfiguration")
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
public class FormsInProcessAutoConfiguration {

    @Bean("formsInProcessStreamOperations")
    @ConditionalOnMissingBean(StreamOperations.class)
    InProcessStreamOperations formsInProcessStreamOperations(ObjectProvider<InProcessUpstream> upstream,
                                                             ApplicationEventPublisher applicationEvents) {
        return new InProcessStreamOperations(upstream, applicationEvents);
    }

    @Bean("formsInProcessTaskRoute")
    @ConditionalOnProperty(name = "forms.embedded.serve-user-tasks", havingValue = "true", matchIfMissing = true)
    // The use cases are the application's scan's to find (@WorkflowEmbeddedApplication scans them).
    @ConditionalOnBean({CreateTaskUseCase.class, CancelTaskUseCase.class, EscalateTaskUseCase.class,
            FormExecutionRepository.class})
    FormsInProcessTaskRoute formsInProcessTaskRoute(CreateTaskUseCase createTaskUseCase,
                                                    CancelTaskUseCase cancelTaskUseCase,
                                                    EscalateTaskUseCase escalateTaskUseCase,
                                                    FormExecutionRepository formExecutionRepository) {
        return new FormsInProcessTaskRoute(createTaskUseCase, cancelTaskUseCase, escalateTaskUseCase,
                formExecutionRepository);
    }
}
