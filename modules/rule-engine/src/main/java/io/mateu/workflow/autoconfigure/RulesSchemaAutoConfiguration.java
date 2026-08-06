package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.schema.ManagedSchema;
import io.mateu.workflow.schema.ManagedSchemaInitializer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Applies the rules engine's schema — the migrations shipped inside this jar under
 * {@code db/migration/rules} — whenever the engine runs on a real database.
 *
 * <p>The workflow engine's {@code WorkflowSchemaAutoConfiguration} carries the reasoning; this is
 * the same arrangement for the rules tables, with its own location, its own history table and its
 * own opt-out ({@code rules.schema.enabled=false}).
 */
@AutoConfiguration(
        after = DataSourceAutoConfiguration.class,
        before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({Flyway.class, DataSource.class, EntityManagerFactory.class})
@EnableConfigurationProperties(RulesSchemaProperties.class)
public class RulesSchemaAutoConfiguration {

    static final String LOCATION = "classpath:db/migration/rules";

    // ObjectProvider rather than @ConditionalOnSingleCandidate(DataSource.class): see
    // ManagedSchemaInitializer. The condition asks whether the data source's bean definition is
    // already visible, and in the standalone apps it is not, so the bean never registered and no
    // migration ever ran.
    @Bean
    @ConditionalOnProperty(prefix = "rules.schema", name = "enabled", matchIfMissing = true)
    ManagedSchemaInitializer rulesSchemaInitializer(
            ObjectProvider<DataSource> dataSources, RulesSchemaProperties properties) {
        return new ManagedSchemaInitializer(
                new ManagedSchema("rules", LOCATION, properties.getTable()), dataSources);
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor rulesSchemaDependsOnPostProcessor() {
        return new EntityManagerFactoryDependsOnPostProcessor(ManagedSchemaInitializer.class);
    }
}
