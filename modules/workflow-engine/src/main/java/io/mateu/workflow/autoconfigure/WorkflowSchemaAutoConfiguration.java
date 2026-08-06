package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.schema.ManagedSchema;
import io.mateu.workflow.schema.ManagedSchemaInitializer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Applies the workflow engine's schema — the migrations shipped inside this jar under
 * {@code db/migration/workflow} — whenever the engine runs on a real database.
 *
 * <p>This is what makes {@code workflow.persistence=jpa} mean the same thing embedded as it does in
 * the standalone orchestrator. It used to mean two different things: the orchestrator carried the
 * migrations in its own resources and ran them through Boot's Flyway, while an embedder got no
 * migrations at all and therefore none of the engine's indexes.
 *
 * <p>It does not touch, replace or interfere with the host application's own Flyway. This is a
 * second, engine-owned Flyway instance over one location and one history table (see
 * {@link ManagedSchema}); {@code spring.flyway.*} keeps meaning exactly what it meant before, for
 * the application's own migrations. Set {@code workflow.schema.enabled=false} to opt out entirely.
 *
 * <p><b>The condition is a database, not {@code workflow.persistence=jpa}.</b> Which repository
 * implementation is active decides who reads the tables; it does not decide whether Hibernate maps
 * the entities. The entities are mapped whenever they are scanned, and under
 * {@code ddl-auto=validate} — what the chart runs — a missing table fails startup regardless of the
 * persistence mode. Tying the schema to the mode would also mean the schema disappears the moment
 * an application forgets to set the property, which is exactly the kind of silent gap this change
 * exists to close. An embedder that keeps the engine in memory and has a data source of its own
 * gets the tables and can decline them with {@code workflow.schema.enabled=false}.
 */
@AutoConfiguration(
        after = DataSourceAutoConfiguration.class,
        before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({Flyway.class, DataSource.class, EntityManagerFactory.class})
@EnableConfigurationProperties(WorkflowSchemaProperties.class)
public class WorkflowSchemaAutoConfiguration {

    static final String LOCATION = "classpath:db/migration/workflow";

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnProperty(prefix = "workflow.schema", name = "enabled", matchIfMissing = true)
    ManagedSchemaInitializer workflowSchemaInitializer(
            DataSource dataSource, WorkflowSchemaProperties properties) {
        return new ManagedSchemaInitializer(
                new ManagedSchema("workflow", LOCATION, properties.getTable()), dataSource);
    }

    /**
     * Keyed on the type rather than a bean name: when the initializer is conditioned away — no data
     * source, or {@code workflow.schema.enabled=false} — there is simply nothing to depend on, and
     * naming a bean that does not exist would fail the context instead.
     */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor workflowSchemaDependsOnPostProcessor() {
        return new EntityManagerFactoryDependsOnPostProcessor(ManagedSchemaInitializer.class);
    }
}
