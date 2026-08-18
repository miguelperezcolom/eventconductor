package io.mateu.workflowdist.support;

import io.mateu.testworker.infra.in.async.TestWorkerKafkaConsumerConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The <b>real</b> test worker — {@code modules/test-worker}, the same code
 * {@code apps/worker-standalone-app} ships — booted against the suite's containers.
 *
 * <p>Not {@link WorkerStub}, and the difference is the point. The stub is programmed from Java
 * inside this JVM: it proves the engine works, and proves nothing about the worker an actual test
 * would use. This context reads its instructions the way that worker does — from the
 * {@code TEST_CONFIG} variable the process carries, over the wire, with no test code reachable from
 * it. If a scenario cannot be expressed, or is expressed and not honoured, these tests fail.
 *
 * <p>It binds to {@code sim-work} rather than {@code work}. Both workers are in the same JVM and on
 * the same broker but in different consumer groups, so a task on a shared topic would be delivered
 * to <em>both</em> and answered twice. Separate topics keep the two suites from answering each
 * other's tasks.
 *
 * <p>The UI package is excluded from the scan: Mateu's Crud pages need a web context this one does
 * not have, and no assertion here goes near them.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "io.mateu.testworker",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.mateu\\.testworker\\.infra\\.in\\.ui\\..*"))
@EntityScan("io.mateu.testworker.infra.out.persistence")
@EnableJpaRepositories("io.mateu.testworker.infra.out.persistence")
public class DistTestWorkerApp {

    // TestWorkerKafkaConsumerConfig is picked up by the scan above; naming it here keeps the
    // import, and the fact that this app is that configuration and its stores, visible.
    static final Class<?> BINDING = TestWorkerKafkaConsumerConfig.class;
}
