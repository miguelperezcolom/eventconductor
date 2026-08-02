package io.mateu.workflow.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Makes Kafka producer sends synchronous by default.
 *
 * <p>This is the engine's outbox guarantee, and it cannot be left to each application's YAML.
 * The relay's contract is "deliver, then mark the row Sent, so a failed delivery is retried" —
 * and with an asynchronous producer there is no such thing as a failed delivery at the moment of
 * sending: {@code send()} returns true as soon as the record is buffered. The row is marked Sent
 * and the message may never reach the broker. Measured on a four-hour run with a ninety-second
 * broker outage in it: 71 of 642 912 messages marked Sent and absent from the topic. Each one is
 * a process that stops, permanently and silently.
 *
 * <p>An application that has some reason to want asynchronous sends can still set the property
 * itself — this is registered as the <em>lowest</em>-precedence source, so anything explicit
 * wins. It should be a considered decision though, because it is the difference between a
 * transactional outbox and a hopeful one.
 *
 * <p>Only applies in {@code kafka} mode; embedded mode has no broker and excludes the binder
 * entirely (see {@link EmbeddedModeAutoConfigurationExcluder}).
 */
public class SynchronousProducerDefaults implements EnvironmentPostProcessor, Ordered {

    private static final String NAME = "eventconductor-producer-defaults";

    private static final String SYNC = "spring.cloud.stream.kafka.default.producer.sync";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!"kafka".equals(environment.getProperty("workflow.mode", "embedded"))) {
            return;
        }
        var sources = environment.getPropertySources();
        if (sources.contains(NAME)) {
            return;
        }
        // addLast, so an application that sets this explicitly — anywhere — still wins.
        sources.addLast(new MapPropertySource(NAME, Map.of(SYNC, "true")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
