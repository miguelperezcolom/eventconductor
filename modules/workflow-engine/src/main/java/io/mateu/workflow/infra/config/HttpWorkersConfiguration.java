package io.mateu.workflow.infra.config;

import io.mateu.workflow.application.httpworkers.HttpTaskInbox;
import io.mateu.workflow.application.httpworkers.HttpTaskQueue;
import io.mateu.workflow.application.httpworkers.HttpTaskRouting;
import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.application.httpworkers.HttpTaskSignal;
import io.mateu.workflow.application.httpworkers.HttpTaskWaiters;
import io.mateu.workflow.application.httpworkers.HttpWorkerMetrics;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.infra.in.scheduler.HttpTaskMaintenance;
import io.mateu.workflow.infra.in.scheduler.SchedulerPass;
import io.mateu.workflow.infra.out.async.HttpWorkerRouter;
import io.mateu.workflow.infra.out.memory.InMemoryHttpTaskQueue;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.JdbcHttpTaskQueue;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Workers over HTTP ({@code workflow.workers.http.enabled=true}): the queue for the persistence in
 * use, the long-poll dispatcher, and the router the downstream publishers ask before sending a
 * task. The REST surface is {@code WorkerApiController}.
 */
@Configuration
@ConditionalOnProperty(name = "workflow.workers.http.enabled", havingValue = "true")
@Slf4j
public class HttpWorkersConfiguration {

    @Bean
    HttpTaskSignal httpTaskSignal() {
        return new HttpTaskSignal();
    }

    @Bean
    HttpWorkerMetrics httpWorkerMetrics(ObjectProvider<MeterRegistry> registry) {
        return new HttpWorkerMetrics(registry::getIfAvailable);
    }

    @Bean
    HttpTaskInbox httpTaskInbox(HttpTaskQueue queue, HttpTaskSignal signal, HttpWorkerMetrics metrics) {
        // The lease clock: wall time, UTC (see HttpTaskService); createdAt is EngineClock's.
        return new HttpTaskInbox(queue, signal, metrics, Clock.systemUTC());
    }

    @Bean
    HttpTaskService httpTaskService(HttpTaskQueue queue, UpstreamEventPublisher upstream, HttpWorkerMetrics metrics) {
        return new HttpTaskService(queue, upstream, metrics, Clock.systemUTC());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    HttpTaskWaiters httpTaskWaiters(HttpTaskService service, HttpTaskSignal signal,
                                    HttpWorkerMetrics metrics, HttpWorkersProperties properties) {
        var routing = new HttpTaskRouting(properties.getTopics());
        if (routing.routesAnything()) {
            log.info("Workers over HTTP: tasks of topics {} are served at /workflow/api/workers", routing);
        } else {
            log.warn("workflow.workers.http.enabled is true but workflow.workers.http.topics is empty: no task is"
                    + " served over HTTP. List the step topics (downstream for steps that name none) or '*'.");
        }
        return new HttpTaskWaiters(service, signal, metrics, new HttpTaskWaiters.Settings(
                properties.getMaxWaiting(), properties.getPollInterval()));
    }

    /** A {@code SchedulerPass}: the test kit runs it on demand; with background schedulers off it never loops. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    HttpTaskMaintenance httpTaskMaintenance(HttpTaskQueue queue, HttpTaskService service, HttpTaskSignal signal,
                                            HttpWorkerMetrics metrics, HttpWorkersProperties properties,
                                            org.springframework.core.env.Environment environment) {
        return new HttpTaskMaintenance(queue, service, signal, metrics, properties.getMaintenanceInterval(),
                properties.getOrphanRetention(),
                environment.getProperty(SchedulerPass.BACKGROUND_PROPERTY, Boolean.class, true));
    }

    @Bean
    HttpWorkerRouter httpWorkerRouter(HttpTaskInbox inbox, HttpWorkersProperties properties) {
        return new HttpWorkerRouter(new HttpTaskRouting(properties.getTopics()), inbox);
    }

    @Configuration
    @ConditionalOnProperty(name = "workflow.workers.http.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
    static class Memory {
        @Bean
        HttpTaskQueue httpTaskQueue() {
            return new InMemoryHttpTaskQueue();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "workflow.workers.http.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
    static class Jpa {
        @Bean
        HttpTaskQueue httpTaskQueue(JdbcTemplate jdbc, DbLockDialect dialect, TransactionTemplate transactions) {
            return new JdbcHttpTaskQueue(jdbc, dialect, transactions);
        }
    }
}
