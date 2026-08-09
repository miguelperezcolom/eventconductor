package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowTracing;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Guarantees that a {@link WorkflowTracing} bean exists — the same hole, the same shape and the
 * same consequence as {@link WorkflowMetricsFallbackAutoConfiguration}, for the other cross-cutting
 * port.
 *
 * <p>{@code WorkflowTracing} is a constructor dependency of {@code StepOverProcessUseCase}, so with
 * Micrometer Tracing on the classpath and {@link WorkflowTracingAutoConfiguration} excluded, the
 * old {@code @ConditionalOnMissingClass(Tracer)} guard left nothing to provide it and the context
 * failed to start. This one is unconditional except for {@code @ConditionalOnMissingBean}, and its
 * name sorts after {@code WorkflowTracingAutoConfiguration} so the real bridge still wins under
 * component scan, where {@code @AutoConfiguration} ordering does not apply.
 */
@AutoConfiguration(after = WorkflowTracingAutoConfiguration.class)
public class WorkflowTracingFallbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkflowTracing.class)
    WorkflowTracing workflowTracing() {
        return WorkflowTracing.NOOP;
    }
}
