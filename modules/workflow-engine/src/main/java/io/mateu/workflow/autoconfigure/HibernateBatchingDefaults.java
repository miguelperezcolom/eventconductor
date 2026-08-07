package io.mateu.workflow.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ships the JDBC batching the engine's write pattern was designed around.
 *
 * <p>The engine writes in bursts by construction. One step transition inserts a step execution,
 * updates the process and inserts every event the transition produced as outbox rows, all in one
 * transaction; the relay then marks a whole claimed batch Sent in another. Both use {@code saveAll}
 * — the repositories were written that way on purpose — and {@code saveAll} without
 * {@code hibernate.jdbc.batch_size} is not a batch at all. It is one statement per row, each its
 * own round trip to a database that is the acknowledged bottleneck of the whole engine.
 *
 * <p>It <em>was</em> configured, in {@code apps/orchestrator-standalone-app}'s YAML, whose comment
 * says as much: "the repositories already saveAll the outbox rows". Which means the engine's design
 * depended on a setting that travelled with one application and reached nobody who embedded the
 * engine — the same shape as the indexes that lived only in that application's Flyway migrations,
 * and cost the same way: invisibly, and only at volume.
 *
 * <h2>Why all four, and not just the batch size</h2>
 *
 * <p>{@code order_inserts} and {@code order_updates} are not decoration. Hibernate flushes in the
 * order the entities were touched, and a batch breaks the moment the table changes; a burst that
 * alternates step, process, outbox, outbox therefore produces batches of one. Sorting by table is
 * what turns the burst into three batches instead of N statements.
 *
 * <p>{@code batch_versioned_data} is the one that would be silently load-bearing here. {@code
 * Process} and {@code StepExecution} carry an optimistic {@code @Version}, and batching versioned
 * updates requires the driver to report per-statement row counts, so where Hibernate cannot assume
 * that it declines to batch them and the optimistic-locking failure would go unnoticed instead.
 * PostgreSQL reports them; saying so explicitly is what keeps the two hot tables in the batch.
 *
 * <p>Contributed at the <em>lowest</em> precedence, so an application that tunes any of these — or
 * turns batching off, which is a legitimate choice for a small embedded deployment — still wins.
 * Inert without JPA: these name Hibernate settings, and nothing reads them where there is no
 * Hibernate.
 */
public class HibernateBatchingDefaults implements EnvironmentPostProcessor, Ordered {

    private static final String NAME = "eventconductor-hibernate-batching-defaults";

    private static final String PREFIX = "spring.jpa.properties.hibernate.";

    static final String BATCH_SIZE = PREFIX + "jdbc.batch_size";
    static final String ORDER_INSERTS = PREFIX + "order_inserts";
    static final String ORDER_UPDATES = PREFIX + "order_updates";
    static final String BATCH_VERSIONED = PREFIX + "jdbc.batch_versioned_data";

    /**
     * Fifty rather than the twenty that application used. The relay claims a hundred messages by
     * default and marks them Sent in one flush, so twenty makes five round trips out of what the
     * batch was chosen to make one; fifty covers the step burst comfortably and halves that again,
     * while staying small enough that a failed batch is cheap to redo.
     */
    private static final String DEFAULT_BATCH_SIZE = "50";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var sources = environment.getPropertySources();
        if (sources.contains(NAME)) {
            return;
        }
        var defaults = new LinkedHashMap<String, Object>();
        defaults.put(BATCH_SIZE, DEFAULT_BATCH_SIZE);
        defaults.put(ORDER_INSERTS, "true");
        defaults.put(ORDER_UPDATES, "true");
        defaults.put(BATCH_VERSIONED, "true");

        // addLast, so anything the application declares — anywhere — still wins.
        sources.addLast(new MapPropertySource(NAME, Map.copyOf(defaults)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
