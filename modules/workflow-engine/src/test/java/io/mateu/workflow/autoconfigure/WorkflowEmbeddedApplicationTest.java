package io.mateu.workflow.autoconfigure;

import com.example.myapp.MyCustomUserBean;
import com.example.myapp.MyTestApp;
import io.mateu.workflow.application.services.CommandDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app annotated with {@link WorkflowEmbeddedApplication} is scanned like one annotated with
 * {@code @SpringBootApplication}: the engine's own package <em>and</em> the package the annotated
 * class sits in. Declaring an explicit {@code basePackages} overrides Spring's default, so before
 * the second {@code @ComponentScan} a user's beans were invisible unless their app class lived
 * under {@code io.mateu.*} — which is not somewhere anyone should have to put their code.
 *
 * <p>Booted through {@link SpringApplicationBuilder} rather than {@code ApplicationContextRunner}
 * on purpose. {@link EmbeddedModeAutoConfigurationExcluder} is an {@code EnvironmentPostProcessor},
 * and those run only inside a {@code SpringApplication} — the context runner builds its context
 * directly, so embedded mode never takes effect there, Cloud Stream loads, and its Kafka admin
 * client spins against a broker that is not there for as long as the JVM lives.
 */
class WorkflowEmbeddedApplicationTest {

    @Test
    void whenAppIsOutsideMateuPackage_scansBothFrameworkAndUserPackage() {
        try (ConfigurableApplicationContext context = embeddedApp()) {
            // The engine, from io.mateu.workflow.
            assertThat(context.getBeansOfType(CommandDispatcher.class)).hasSize(1);

            // The user's own bean, sitting next to the annotated class in com.example.myapp.
            assertThat(context.getBeansOfType(MyCustomUserBean.class)).hasSize(1);
        }
    }

    private ConfigurableApplicationContext embeddedApp() {
        return new SpringApplicationBuilder(MyTestApp.class)
                .web(WebApplicationType.NONE)
                .properties("workflow.mode=embedded", "workflow.persistence=memory")
                .run();
    }
}
