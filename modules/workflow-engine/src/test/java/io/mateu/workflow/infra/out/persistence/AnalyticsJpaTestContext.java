package io.mateu.workflow.infra.out.persistence;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.Map;

/**
 * The smallest Spring context the analytics read model runs in: the engine's entities and the
 * read model's package-private repositories — on whatever {@link DataSource}
 * the test hands in (H2 or PostgreSQL), over the schema the engine's own migrations created.
 * {@code ddl-auto} stays off, so the queries meet the migrated tables, not ones Hibernate invented.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackageClasses = AnalyticsRollupRepositories.class,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                ProcessCreatedDailyRepository.class, ProcessFinishedDailyRepository.class,
                ProcessStatusDailyRepository.class, ProcessDurationDailyRepository.class,
                StepStatusDailyRepository.class, StepDurationDailyRepository.class,
                AnalyticsProjectionStateRepository.class, AnalyticsSourceRepository.class,
                LiveOverlayRepository.class, CountedProcessRepository.class,
                CountedStepRepository.class,
                // Not analytics: the process listing's queries, on the same migrated schemas.
                ProcessEntityRepository.class, ImportedDefinitionSourceEntityRepository.class}))
class AnalyticsJpaTestContext {

    static AnnotationConfigApplicationContext open(DataSource dataSource) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(AnalyticsJpaTestContext.class);
        context.refresh();
        return context;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        // The whole persistence package, as the engine's own scan has it; only the analytics
        // repositories are created, and with ddl-auto off the other entities cost a mapping each.
        factory.setPackagesToScan(ProcessEntity.class.getPackageName());
        // Spring Boot's naming, so workflowDefinitionId meets workflow_definition_id as it does in
        // a real deployment.
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
