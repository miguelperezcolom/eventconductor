package io.mateu.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The JPA half of the worker, and only when it is asked for.
 *
 * <p>These two annotations used to sit on the application class, where they applied always. The
 * stores are properly conditional — {@code InMemoryReceivedTaskStore} on
 * {@code worker.persistence=memory}, the JPA ones on {@code jpa} — but the repository interfaces
 * they wrap carry no condition of their own, and cannot: Spring Data scans for them, and scanning is
 * what {@code @EnableJpaRepositories} turns on. So under the {@code memory} profile the repositories
 * were still found, still needed an {@code entityManagerFactory}, and the application failed to
 * start — the profile the docs recommend for CI, which excluded {@code DataSourceAutoConfiguration}
 * and could not have helped: excluding an auto-configuration does not stop repository scanning.
 *
 * <p>Conditional on the property rather than on the profile, because the property is what the stores
 * already switch on and a host embedding this worker may set it without using profiles at all.
 * {@code matchIfMissing = true} keeps the default a database-backed worker, which is what
 * {@code application.yaml} says and what every existing deployment has.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "worker.persistence", havingValue = "jpa", matchIfMissing = true)
@EntityScan("io.mateu.testworker.infra.out.persistence")
@EnableJpaRepositories("io.mateu.testworker.infra.out.persistence")
public class WorkerJpaPersistenceConfiguration {
}
