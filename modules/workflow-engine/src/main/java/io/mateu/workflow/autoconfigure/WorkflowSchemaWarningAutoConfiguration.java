package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.schema.ManagedSchemaInitializer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Warns at startup when the engine runs on a database whose schema it is not managing.
 *
 * <p>The same shape as the {@code workflow.persistence=memory} warning, and for the same reason: an
 * unmanaged schema is not a visible failure, it is a slow one. Hibernate's {@code ddl-auto=update}
 * path creates tables and primary keys and emits no index DDL at all, so the engine starts, works,
 * and turns every deadline scan, outbox claim and message correlation into a sequential scan.
 * Measured on a cluster, that pinned PostgreSQL at 750m of CPU and cut throughput to a fraction —
 * with nothing in the log to suggest why.
 *
 * <p>Reached in two ways: Flyway is not on the classpath (it is an optional dependency of the
 * engine), or {@code workflow.schema.enabled=false}. Both are legitimate — a DBA-applied schema, a
 * host application that runs the engine's migrations through its own Flyway — which is why this
 * warns rather than refuses to start.
 */
@AutoConfiguration(after = WorkflowSchemaAutoConfiguration.class)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
public class WorkflowSchemaWarningAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ManagedSchemaInitializer.class)
    UnmanagedSchemaWarning workflowUnmanagedSchemaWarning() {
        return new UnmanagedSchemaWarning();
    }

    @Slf4j
    public static class UnmanagedSchemaWarning {

        @PostConstruct
        void warn() {
            log.warn("workflow.persistence=jpa but the engine is NOT managing its schema: its "
                    + "migrations were not applied, so the database may be missing the engine's "
                    + "indexes (Hibernate's ddl-auto=update creates none) and every deadline scan, "
                    + "outbox claim and message correlation degrades to a sequential scan. Add "
                    + "org.flywaydb:flyway-core (plus the driver module, e.g. "
                    + "flyway-database-postgresql) to let the engine apply its own schema, or set "
                    + "workflow.schema.enabled=false to state that something else owns it.");
        }
    }
}
