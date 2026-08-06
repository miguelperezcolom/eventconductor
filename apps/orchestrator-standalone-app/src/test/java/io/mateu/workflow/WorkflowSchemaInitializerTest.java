package io.mateu.workflow;

import io.mateu.workflow.schema.ManagedSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the engine actually applies its own migrations <em>in this application</em>.
 *
 * <p>{@code WorkflowSchemaAutoConfigurationTest} already covers the autoconfiguration, but it builds
 * its context with an {@code ApplicationContextRunner} listing the two autoconfigurations by hand,
 * which orders them by construction. That is not the shape this app boots in, and in the real one
 * the initializer was silently absent — {@code @ConditionalOnSingleCandidate(DataSource.class)} saw
 * no data source definition yet. The app still started, because {@code ddl-auto} defaults to
 * {@code update} and Hibernate built the schema instead; under the chart's {@code DDL_AUTO=validate}
 * it died on the first missing table.
 */
@SpringBootTest
class WorkflowSchemaInitializerTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theEngineManagesItsOwnSchema() {
        assertThat(context.getBeansOfType(ManagedSchemaInitializer.class)).hasSize(1);
    }
}
