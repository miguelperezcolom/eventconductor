package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

/**
 * The smallest Spring context the forms persistence runs in, on whatever database the test hands in,
 * over the schema the forms engine's own migrations created — and with {@code hbm2ddl=validate}, so
 * the context does not even open unless every mapping matches a migrated column: the migrations and
 * the entities are held to each other on H2 and on PostgreSQL alike.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = FormExecutionEntityRepository.class)
class FormsJpaTestContext {

    /** Applies the forms migrations, as {@code FormsSchemaAutoConfiguration} does in a deployment. */
    static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/forms")
                .table("flyway_schema_history_forms")
                .load()
                .migrate();
    }

    static AnnotationConfigApplicationContext open(DataSource dataSource) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(FormsJpaTestContext.class);
        context.refresh();
        return context;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan(FormExecutionEntity.class.getPackageName());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
