package io.mateu.workflow.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's write pattern — {@code saveAll} of a step, a process and N outbox rows per
 * transition — is one statement per row without these, which is how it ran everywhere except the
 * one application whose YAML happened to set them.
 */
class HibernateBatchingDefaultsTest {

    private final HibernateBatchingDefaults postProcessor = new HibernateBatchingDefaults();

    @Test
    void contributesTheBatchingTheRepositoriesWereWrittenFor() {
        var environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(HibernateBatchingDefaults.BATCH_SIZE)).isEqualTo("50");
        assertThat(environment.getProperty(HibernateBatchingDefaults.ORDER_INSERTS)).isEqualTo("true");
        assertThat(environment.getProperty(HibernateBatchingDefaults.ORDER_UPDATES)).isEqualTo("true");
        assertThat(environment.getProperty(HibernateBatchingDefaults.BATCH_VERSIONED)).isEqualTo("true");
    }

    @Test
    void theySurviveTheBindingIntoThePropertiesMapAndNotJustTheEnvironment() {
        // The claim worth pinning. Boot hands Hibernate whatever binds under spring.jpa.properties
        // as a map, and a key under a namespace that does not bind is not rejected — it would sit
        // in the environment looking configured while Hibernate ran unbatched, which is
        // indistinguishable from the bug this fixes. Binding a Map here rather than Boot's
        // JpaProperties keeps the check independent of which module that class lives in.
        var environment = new MockEnvironment();
        postProcessor.postProcessEnvironment(environment, null);

        var bound = Binder.get(environment)
                .bind("spring.jpa.properties", Bindable.mapOf(String.class, String.class))
                .orElseThrow(() -> new AssertionError(
                        "nothing bound under spring.jpa.properties: the namespace is wrong, and "
                                + "nothing else in the build would have said so"));

        assertThat(bound)
                .containsEntry("hibernate.jdbc.batch_size", "50")
                .containsEntry("hibernate.order_inserts", "true")
                .containsEntry("hibernate.order_updates", "true")
                .containsEntry("hibernate.jdbc.batch_versioned_data", "true");
    }

    @Test
    void anApplicationThatTunesOrDisablesBatchingStillWins() {
        // Turning it off is a legitimate choice for a small embedded deployment, so it has to be
        // possible without fighting the engine.
        var environment = new MockEnvironment()
                .withProperty(HibernateBatchingDefaults.BATCH_SIZE, "0")
                .withProperty(HibernateBatchingDefaults.ORDER_INSERTS, "false");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(HibernateBatchingDefaults.BATCH_SIZE)).isEqualTo("0");
        assertThat(environment.getProperty(HibernateBatchingDefaults.ORDER_INSERTS)).isEqualTo("false");
    }

    @Test
    void isIdempotentWhenSeveralModulesOnTheClasspathRegisterIt() {
        var environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, null);
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(HibernateBatchingDefaults.BATCH_SIZE)).isEqualTo("50");
    }

    @Test
    void travelsWithTheEngineSoNoApplicationHasToRegisterIt() {
        // The whole point: it only reaches an embedder if Boot finds it, and Boot only finds it
        // through this file. The previous home was one application's YAML.
        assertThat(registeredEnvironmentPostProcessors())
                .anyMatch(entry -> entry.contains(HibernateBatchingDefaults.class.getName()));
    }

    private static java.util.List<String> registeredEnvironmentPostProcessors() {
        var registrations = new java.util.ArrayList<String>();
        try {
            for (var url : java.util.Collections.list(
                    HibernateBatchingDefaultsTest.class.getClassLoader().getResources("META-INF/spring.factories"))) {
                var factories = new java.util.Properties();
                try (var in = url.openStream()) {
                    factories.load(in);
                }
                var registered = factories.getProperty("org.springframework.boot.env.EnvironmentPostProcessor");
                if (registered != null) {
                    registrations.add(registered);
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return registrations;
    }
}
