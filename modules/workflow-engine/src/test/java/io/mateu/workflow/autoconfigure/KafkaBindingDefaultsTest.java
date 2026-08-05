package io.mateu.workflow.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's consumers take a batch — {@code Consumer<Message<List<DomainEvent>>>} — because a
 * poll batch is committed as one transaction per process. Without {@code batch-mode} the binder
 * delivers one unconverted record and the first event of the first process dies with
 * {@code ClassCastException: class [B cannot be cast to class java.util.List}, retries exhaust,
 * and the outbox is dead-lettered event by event.
 *
 * <p>That property lived in the standalone application's YAML, in the benchmark and in the
 * distributed tests — everywhere we ran the engine, and nowhere an application embedding it would
 * look. It appeared in no documentation.
 */
class KafkaBindingDefaultsTest {

    private static final String OUTBOX_BATCH =
            "spring.cloud.stream.bindings.consumeOutbox-in-0.consumer.batch-mode";
    private static final String UPSTREAM_BATCH =
            "spring.cloud.stream.bindings.consumeUpstream-in-0.consumer.batch-mode";
    private static final String OUTBOX_GROUP =
            "spring.cloud.stream.bindings.consumeOutbox-in-0.group";
    private static final String UPSTREAM_GROUP =
            "spring.cloud.stream.bindings.consumeUpstream-in-0.group";

    private final KafkaBindingDefaults defaults = new KafkaBindingDefaults();

    private MockEnvironment environment() {
        return new MockEnvironment();
    }

    @Test
    void bothConsumersGetBatchMode() {
        var environment = environment();

        defaults.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(OUTBOX_BATCH)).isEqualTo("true");
        assertThat(environment.getProperty(UPSTREAM_BATCH)).isEqualTo("true");
    }

    @Test
    void destinationsAndDeadLetterAreWired() {
        var environment = environment();

        defaults.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.cloud.stream.bindings.consumeOutbox-in-0.destination"))
                .isEqualTo("outbox");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.consumeUpstream-in-0.destination"))
                .isEqualTo("upstream");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.outbox.destination")).isEqualTo("outbox");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.upstream.destination")).isEqualTo("upstream");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.downstream.destination")).isEqualTo("downstream");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.deadLetter.destination"))
                .isEqualTo("dead-letter");
    }

    @Test
    void eachConsumerGetsItsOwnGroup() {
        // A group whose members subscribe to different topics is assigned per topic by the range
        // assignor, and with mixed subscriptions it leaves partitions with no consumer at all.
        var environment = environment();

        defaults.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(OUTBOX_GROUP)).isNotEqualTo(environment.getProperty(UPSTREAM_GROUP));
    }

    @Test
    void whatTheApplicationDeclaresWins() {
        var environment = environment();
        environment.getPropertySources().addFirst(new MapPropertySource("application",
                Map.of(OUTBOX_GROUP, "my-own-group", OUTBOX_BATCH, "false")));

        defaults.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(OUTBOX_GROUP)).isEqualTo("my-own-group");
        assertThat(environment.getProperty(OUTBOX_BATCH)).isEqualTo("false");
        // …and the ones it did not declare are still there.
        assertThat(environment.getProperty(UPSTREAM_BATCH)).isEqualTo("true");
    }

    @Test
    void contributingTwiceAddsOneSource() {
        var environment = environment();

        defaults.postProcessEnvironment(environment, null);
        int after = environment.getPropertySources().size();
        defaults.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().size()).isEqualTo(after);
    }

    @Test
    void theFunctionDefinitionIsLeftToTheApplication() {
        // It lists the functions this application composes — the engine's two, plus a worker's or
        // the forms engine's when they are on the classpath. A default here would drop the others.
        var environment = environment();

        defaults.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.cloud.function.definition")).isNull();
    }
}
