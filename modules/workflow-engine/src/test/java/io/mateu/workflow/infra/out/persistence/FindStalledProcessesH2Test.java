package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ProcessEntityRepository#findStalled} against the migrated schema on H2 (PostgreSQL
 * mode): a RUNNING process with no step left to run is reported, and one whose only unfinished
 * step is waiting out a retry backoff or queued behind a lock is not — those are still going.
 */
class FindStalledProcessesH2Test {

    static final LocalDateTime LONG_AGO = LocalDateTime.now().minusHours(3);
    static final LocalDateTime IDLE_BEFORE = LocalDateTime.now().minusHours(1);

    DataSource dataSource;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    ProcessEntityRepository repository;

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(
            basePackageClasses = ProcessEntityRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = ProcessEntityRepository.class))
    static class Context {

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
    }

    @BeforeEach
    void open() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:stalled-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        PostgresTestDatabase.migrate(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(Context.class);
        context.refresh();
        jdbc = new JdbcTemplate(dataSource);
        repository = context.getBean(ProcessEntityRepository.class);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void aRunningProcessWithNothingLeftToRunIsStalled() {
        process("p-1", ProcessStatus.RUNNING);
        step("p-1-a", "p-1", StepExecutionStatus.COMPLETED, LONG_AGO);
        step("p-1-b", "p-1", StepExecutionStatus.CREATED, null);

        assertThat(repository.findStalled(IDLE_BEFORE, PageRequest.of(0, 20))).containsExactly("p-1");
    }

    @ParameterizedTest
    @EnumSource(value = StepExecutionStatus.class,
            names = {"PENDING", "RUNNING", "AWAITING_RETRY", "WAITING_ON_LOCK"})
    void aStepThatIsStillGoingKeepsTheProcessOffTheReport(StepExecutionStatus live) {
        process("p-1", ProcessStatus.RUNNING);
        step("p-1-a", "p-1", StepExecutionStatus.COMPLETED, LONG_AGO);
        step("p-1-b", "p-1", live, null);

        assertThat(repository.findStalled(IDLE_BEFORE, PageRequest.of(0, 20))).isEmpty();
    }

    private void process(String id, ProcessStatus status) {
        jdbc.update("INSERT INTO process_entity (id, business_key, name, status, workflow_definition_id, created) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, "bk-" + id, id, status.name(), "wd", Timestamp.valueOf(LONG_AGO));
    }

    private void step(String id, String processId, StepExecutionStatus status, LocalDateTime finishedAt) {
        jdbc.update("INSERT INTO step_execution_entity (id, process_id, workflow_definition_id, step_id, "
                        + "status, _order, finished_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, processId, "wd", id, status.name(), 1,
                finishedAt == null ? null : Timestamp.valueOf(finishedAt));
    }
}
