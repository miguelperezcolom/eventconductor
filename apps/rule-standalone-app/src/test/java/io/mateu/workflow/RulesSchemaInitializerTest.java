package io.mateu.workflow;

import io.mateu.workflow.schema.ManagedSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the engine actually applies its own migrations <em>in this application</em>. Same reasoning
 * as the orchestrator's {@code WorkflowSchemaInitializerTest}: the autoconfiguration's own test
 * orders the autoconfigurations by construction, and only a real boot of this application shows
 * whether the initializer is registered at all.
 */
@SpringBootTest
class RulesSchemaInitializerTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theEngineManagesItsOwnSchema() {
        assertThat(context.getBeansOfType(ManagedSchemaInitializer.class)).hasSize(1);
    }
}
