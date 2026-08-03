package io.mateu.workflow.worker;

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
 * <p>It is also the prerequisite of {@link WorkerReply}, which is why this lives here, beside it,
 * rather than in the engine. A worker — the forms engine answering a USER_TASK, the rule runtime
 * answering a RULE step, or anyone else's — checks a {@code false} that an asynchronous binding
 * never returns, so without this the retry-and-throw in {@code WorkerReply} is decoration. Every
 * module that can reply to the engine depends on {@code shared}, so putting it here means no
 * application has to remember.
 *
 * <p>An application that has some reason to want asynchronous sends can still set the property
 * itself — this is registered as the <em>lowest</em>-precedence source, so anything explicit
 * wins. It should be a considered decision though, because it is the difference between a
 * transactional outbox and a hopeful one.
 *
 * <p>Contributed unconditionally, deliberately. It used to apply only when
 * {@code workflow.mode=kafka}, which is fine for an application that runs the engine and wrong
 * for one that only answers it: a worker turns the binder on by having it on the classpath, not
 * by declaring a mode, so the property quietly did not arrive and {@code WorkerReply} had nothing
 * to check. Where no Kafka producer exists the property is inert — it names a Kafka binding, and
 * embedded mode excludes the binder outright — so there is nothing to be gained by guessing which
 * applications need it.
 */
public class SynchronousProducerDefaults implements EnvironmentPostProcessor, Ordered {

    private static final String NAME = "eventconductor-producer-defaults";

    private static final String SYNC = "spring.cloud.stream.kafka.default.producer.sync";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
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
