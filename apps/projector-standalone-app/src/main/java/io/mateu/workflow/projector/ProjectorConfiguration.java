package io.mateu.workflow.projector;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexProjection;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.processindex.JdbcProcessIndexStore;
import io.mateu.workflow.schema.ManagedSchema;
import io.mateu.workflow.schema.ManagedSchemaInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;
import java.util.List;
import java.util.function.Consumer;

/**
 * The whole projector, in three beans: the read database's schema, the store, and the consumer.
 */
@Configuration
public class ProjectorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProjectorConfiguration.class);

    /**
     * The read database's own schema — one table, its own migration set, its own history table. Not
     * Boot's Flyway, for the same reason the engine does not use it either: this is a schema owned by
     * a component, and it must not collide with whatever else the database holds.
     */
    @Bean
    public ManagedSchemaInitializer processIndexSchemaInitializer(ObjectProvider<DataSource> dataSources) {
        return new ManagedSchemaInitializer(
                new ManagedSchema("process-index", "classpath:db/migration/processindex",
                        "eventconductor_process_index_history"),
                dataSources);
    }

    /** Depends on the schema: the store probes the database on construction to pick its upsert. */
    @Bean
    @DependsOn("processIndexSchemaInitializer")
    public ProcessIndexRepository processIndexStore(DataSource dataSource) {
        return new JdbcProcessIndexStore(dataSource);
    }

    /**
     * The consumer of the shared projection topic.
     *
     * <p>Batch mode, like every other consumer in the fleet, so a poll batch is one unit of work
     * rather than one transaction per record. There is no per-process grouping here and no need for
     * one: the projection is a single idempotent upsert whose staleness guard makes redelivery and
     * out-of-order arrival both no-ops, so a redelivered batch simply re-applies to the same state.
     *
     * <p>Anything that is not a {@link ProcessStatusChanged} is ignored rather than dead-lettered.
     * The topic is dedicated to this event, so another type on it means someone pointed a binding at
     * the wrong destination — a configuration mistake, which a projector that keeps consuming makes
     * visible in a log line instead of turning into a stalled partition.
     */
    @Bean
    public Consumer<List<DomainEvent>> consumeProcessIndex(ProcessIndexRepository store) {
        return events -> {
            for (var event : events) {
                if (event instanceof ProcessStatusChanged statusChanged) {
                    ProcessIndexProjection.apply(store, statusChanged);
                } else {
                    log.warn("Ignoring a {} on the process-index topic: this channel carries "
                                    + "ProcessStatusChanged only — check the producing binding",
                            event.getClass().getSimpleName());
                }
            }
        };
    }
}
