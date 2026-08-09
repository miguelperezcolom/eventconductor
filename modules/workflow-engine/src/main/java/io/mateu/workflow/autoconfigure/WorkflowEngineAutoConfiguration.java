package io.mateu.workflow.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Ordering anchor for the engine's auto-configurations, and nothing else.
 *
 * <p>It used to carry the {@code WorkflowMetrics} and {@code WorkflowTracing} no-op fallbacks,
 * each gated on the corresponding Micrometer class being absent from the classpath. That guard was
 * there to stop the no-op from shadowing the real implementation under component scan, where
 * class-name order decides — and it did that job — but it also meant the fallbacks did not apply
 * when the Micrometer classes were present and the real auto-configuration had been
 * <em>excluded</em>. Nothing then provided the beans, and since both are constructor dependencies
 * of engine beans, the context could not start at all.
 *
 * <p>They now live in {@link WorkflowMetricsFallbackAutoConfiguration} and
 * {@link WorkflowTracingFallbackAutoConfiguration}, whose names sort after the real ones, so they
 * fill the hole without ever winning the race.
 *
 * <p>This class stays, empty, because {@link WorkflowMetricsAutoConfiguration} and
 * {@link WorkflowTracingAutoConfiguration} order themselves relative to it and because a host may
 * name it in {@code spring.autoconfigure.exclude} — where a missing class is a startup failure, not
 * a no-op.
 */
@AutoConfiguration
public class WorkflowEngineAutoConfiguration {
}
