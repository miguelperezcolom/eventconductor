package io.mateu.workflow.worker;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is the property that turns {@link WorkerReply}'s retry-and-throw from decoration into a
 * guarantee: an asynchronous producer returns true the moment the record is buffered, so the
 * refusal it checks for never arrives. It lives beside WorkerReply, rather than in the engine,
 * precisely so that a module which only replies — the forms engine, the rule runtime, a worker of
 * someone else's — does not have to remember to set it.
 */
class SynchronousProducerDefaultsTest {

    private static final String SYNC = "spring.cloud.stream.kafka.default.producer.sync";

    private final SynchronousProducerDefaults postProcessor = new SynchronousProducerDefaults();

    @Test
    void makesProducerSendsSynchronousInKafkaMode() {
        var environment = new MockEnvironment().withProperty("workflow.mode", "kafka");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SYNC)).isEqualTo("true");
    }

    @Test
    void appliesToAWorkerThatNeverDeclaresAMode() {
        // A worker turns the binder on by having it on the classpath, not by declaring a mode.
        // Guarding this on workflow.mode=kafka is what left the demo workers without it.
        var environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SYNC)).isEqualTo("true");
    }

    @Test
    void isHarmlessInEmbeddedMode() {
        // There is no Kafka producer there — the binder is excluded outright — so the property
        // names nothing and costs nothing.
        var environment = new MockEnvironment().withProperty("workflow.mode", "embedded");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SYNC)).isEqualTo("true");
    }

    @Test
    void neverOverridesAnApplicationThatAskedForAsynchronousSends() {
        // Contributed at the lowest precedence, so an explicit choice — a considered one, one
        // hopes — still wins.
        var environment = new MockEnvironment()
                .withProperty("workflow.mode", "kafka")
                .withProperty(SYNC, "false");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SYNC)).isEqualTo("false");
    }

    @Test
    void travelsWithTheModuleSoNoApplicationHasToRegisterIt() throws Exception {
        // The class only runs if Boot finds it, and Boot only finds it through this file. Every
        // module that can reply to the engine depends on this one, so the registration living
        // here is what makes it reach them.
        var registrations = new java.util.ArrayList<String>();
        for (var url : java.util.Collections.list(
                getClass().getClassLoader().getResources("META-INF/spring.factories"))) {
            var factories = new java.util.Properties();
            try (var in = url.openStream()) {
                factories.load(in);
            }
            var registered = factories.getProperty("org.springframework.boot.env.EnvironmentPostProcessor");
            if (registered != null) {
                registrations.add(registered);
            }
        }

        assertThat(registrations)
                .anyMatch(entry -> entry.contains(SynchronousProducerDefaults.class.getName()));
    }

    @Test
    void isIdempotentWhenSeveralModulesOnTheClasspathRegisterIt() {
        var environment = new MockEnvironment().withProperty("workflow.mode", "kafka");

        postProcessor.postProcessEnvironment(environment, null);
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SYNC)).isEqualTo("true");
    }
}
