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
 * Applies the forms engine's schema — the migrations shipped inside this jar under
 * {@code db/migration/forms} — whenever the engine runs on a real database.
 *
 * <p>The workflow engine's {@code WorkflowSchemaAutoConfiguration} carries the reasoning; this is
 * the same arrangement for the forms tables, with its own location, its own history table and its
 * own opt-out ({@code forms.schema.enabled=false}).
 */
@AutoConfiguration(
        after = DataSourceAutoConfiguration.class,
        before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({Flyway.class, DataSource.class, EntityManagerFactory.class})
@EnableConfigurationProperties(FormsSchemaProperties.class)
public class FormsSchemaAutoConfiguration {

    static final String LOCATION = "classpath:db/migration/forms";

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnProperty(prefix = "forms.schema", name = "enabled", matchIfMissing = true)
    ManagedSchemaInitializer formsSchemaInitializer(
            DataSource dataSource, FormsSchemaProperties properties) {
        return new ManagedSchemaInitializer(
                new ManagedSchema("forms", LOCATION, properties.getTable()), dataSource);
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor formsSchemaDependsOnPostProcessor() {
        return new EntityManagerFactoryDependsOnPostProcessor(ManagedSchemaInitializer.class);
    }
}
