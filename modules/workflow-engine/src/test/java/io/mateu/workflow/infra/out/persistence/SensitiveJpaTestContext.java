package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

/**
 * The repositories a sensitive variable is written through, over a migrated schema (ddl-auto off). Not a
 * {@code @Configuration}: registered explicitly by {@link #open}, so the engine's own component scan in other
 * tests never picks it up next to {@code AnalyticsJpaTestContext}.
 */
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackageClasses = ProcessEntityRepository.class,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                ProcessEntityRepository.class, StepExecutionEntityRepository.class,
                OutboxMessageEntityRepository.class, WorkflowDefinitionEntityRepository.class}))
class SensitiveJpaTestContext {

    static AnnotationConfigApplicationContext open(DataSource dataSource) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(SensitiveJpaTestContext.class);
        context.refresh();
        return context;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan(ProcessEntity.class.getPackageName());
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
