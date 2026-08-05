package io.mateu.workflow.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the engine's own Kafka bindings, so an application does not have to know them.
 *
 * <p>The engine's consumers are declared as {@code Consumer<Message<List<DomainEvent>>>} — a
 * batch of events, because a poll batch is committed as one transaction per process. A binding
 * without {@code batch-mode} delivers one record at a time and does not convert it, so the
 * payload arrives as a {@code byte[]} and the very first event dies with
 *
 * <pre>java.lang.ClassCastException: class [B cannot be cast to class java.util.List</pre>
 *
 * retries exhaust, and every outbox event is dead-lettered. The property was set in the
 * standalone application's YAML, in the benchmark and in the distributed tests — everywhere the
 * engine was run by us, and nowhere an application embedding it would find. It was not in the
 * documentation at all, so the copy-paste that came closest to working was the one that produced
 * that exception on the first process.
 *
 * <p>Groups are per binding, not one shared between them, and that is not cosmetic: a consumer
 * group whose members subscribe to different topics is assigned by the default range assignor per
 * topic, and with mixed subscriptions it leaves partitions with no consumer at all — messages
 * nobody reads and processes that never move. The documented snippet used one group for both
 * bindings, which is exactly that shape.
 *
 * <p>Everything here is contributed as the <em>lowest</em>-precedence property source, so an
 * application that sets any of it — a different destination, a group of its own, batch-mode off —
 * still wins. What it no longer has to do is set all of it to get a working engine.
 *
 * <p>Contributed unconditionally, like the producer defaults in {@code shared}: these name Kafka
 * bindings, embedded mode excludes the binder outright, and Spring Cloud Stream binds the
 * functions that exist rather than the properties that do. Guessing which applications are
 * orchestrators is how the property came to be missing from the ones that were.
 *
 * <p>Not contributed: {@code spring.cloud.function.definition}. It lists the functions this
 * application composes — the engine's two, plus a worker's or the forms engine's if they are on
 * the classpath — so it belongs to whoever assembles the application, and a default here would
 * silently drop the others.
 */
public class KafkaBindingDefaults implements EnvironmentPostProcessor, Ordered {

    private static final String NAME = "eventconductor-kafka-binding-defaults";

    private static final String PREFIX = "spring.cloud.stream.bindings.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var sources = environment.getPropertySources();
        if (sources.contains(NAME)) {
            return;
        }
        var defaults = new LinkedHashMap<String, Object>();

        // Consumers. batch-mode is the one that cannot be left out: the functions take a List.
        defaults.put(PREFIX + "consumeOutbox-in-0.destination", "outbox");
        defaults.put(PREFIX + "consumeOutbox-in-0.group", "orchestrator-outbox");
        defaults.put(PREFIX + "consumeOutbox-in-0.consumer.batch-mode", "true");
        defaults.put(PREFIX + "consumeUpstream-in-0.destination", "upstream");
        defaults.put(PREFIX + "consumeUpstream-in-0.group", "orchestrator-upstream");
        defaults.put(PREFIX + "consumeUpstream-in-0.consumer.batch-mode", "true");

        // Producers (StreamBridge targets), including where unprocessable events are parked.
        defaults.put(PREFIX + "outbox.destination", "outbox");
        defaults.put(PREFIX + "upstream.destination", "upstream");
        defaults.put(PREFIX + "downstream.destination", "downstream");
        defaults.put(PREFIX + "deadLetter.destination", "dead-letter");

        // addLast, so anything the application declares — anywhere — still wins.
        sources.addLast(new MapPropertySource(NAME, Map.copyOf(defaults)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
