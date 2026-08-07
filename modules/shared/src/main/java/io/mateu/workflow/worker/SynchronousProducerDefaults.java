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
 * <p><b>The three Kafka settings beside it are the other half of the same guarantee, and they are
 * pinned rather than inherited.</b> {@code enable.idempotence=true}, {@code acks=all} and
 * {@code max.in.flight.requests.per.connection=5} are the client's own defaults on Kafka 3.0 and
 * later, so until now the engine got them by luck: undeclared, unasserted, and one
 * {@code enable.idempotence=false} in somebody's YAML away from disappearing. Two things break
 * quietly if they do. Without {@code acks=all} a send is acknowledged by a leader that has not
 * replicated it, so a broker failover loses messages the relay has already marked Sent — the same
 * silent loss the synchronous send exists to prevent, arriving by a different door. And without
 * idempotence a retried send can be delivered out of order, which would undo the per-process
 * ordering that keying events by process bought: a retry is invisible and asynchronous, so the
 * reordering would surface only under broker stress, in production, as a process that took a
 * transition twice or took them backwards.
 *
 * <p>They are also the precondition for ever batching the relay's sends. Awaiting one barrier per
 * batch instead of one ack per message means more than one request in flight, and more than one
 * request in flight is safe <em>only</em> under an idempotent producer. Fixing them here is what
 * makes that change a tuning decision rather than a correctness gamble.
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

    private static final String CONFIG = "spring.cloud.stream.kafka.default.producer.configuration.";

    static final String IDEMPOTENCE = CONFIG + "enable.idempotence";
    static final String ACKS = CONFIG + "acks";
    static final String MAX_IN_FLIGHT = CONFIG + "max.in.flight.requests.per.connection";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var sources = environment.getPropertySources();
        if (sources.contains(NAME)) {
            return;
        }
        // addLast, so an application that sets these explicitly — anywhere — still wins.
        sources.addLast(new MapPropertySource(NAME, Map.of(
                SYNC, "true",
                IDEMPOTENCE, "true",
                ACKS, "all",
                MAX_IN_FLIGHT, "5")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
