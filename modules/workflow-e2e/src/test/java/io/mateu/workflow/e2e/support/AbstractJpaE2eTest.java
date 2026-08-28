package io.mateu.workflow.e2e.support;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Base for JPA durability e2e tests. Runs the engine in {@code embedded} mode with
 * {@code jpa} persistence against an in-memory H2 (PostgreSQL compatibility), so the real
 * {@code EmbeddedOutboxRelay}, JDBC advisory locks and JPA repositories are exercised.
 *
 * <p>Unlike the memory-mode harness, execution here is ASYNCHRONOUS: events flow through
 * the outbox table and are drained by the relay's poll loop, so tests fire the creation
 * event and then {@link #awaitStatus(String, ProcessStatus) await} the terminal state.
 */
@SpringBootTest(classes = io.mateu.e2ejpa.durability.JpaE2eTestApplication.class)
@Import(E2eConfig.class)
@TestPropertySource(properties = {
        "workflow.mode=embedded",
        "workflow.persistence=jpa",
        "workflow.cron-enabled=false",
        "workflow.outbox-poll-interval-ms=50",
        "workflow.timeout-scan-interval-ms=200",
        "spring.datasource.url=jdbc:h2:mem:jpa-e2e;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "spring.main.web-application-type=none",
})
// Durability tests exercise real DB + relay threads + JVM-static H2 advisory locks; a fresh
// context per method keeps them from contending on that shared, stateful infrastructure.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public abstract class AbstractJpaE2eTest {

    protected static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired protected TestWorker worker;
    @Autowired protected ProcessRepository processRepository;
    @Autowired protected StepExecutionRepository stepExecutionRepository;
    @Autowired protected ProcessUpstreamEventUseCase processUpstreamEventUseCase;
    @Autowired protected OutboxMessageEntityRepository outboxRepository;

    @BeforeEach
    void resetWorker() {
        worker.clear();
    }

    protected void createProcess(String definitionId, String businessKey, Variable... variables) {
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
                new ProcessCreationRequested(definitionId, businessKey, List.of(variables))));
    }

    protected Optional<Process> processOpt(String businessKey) {
        return processRepository.findByBusinessKey(businessKey);
    }

    protected Process process(String businessKey) {
        return processOpt(businessKey).orElseThrow();
    }

    protected List<StepExecution> steps(String businessKey) {
        return stepExecutionRepository.findByProcess(process(businessKey));
    }

    protected StepExecution step(String businessKey, String stepId) {
        return steps(businessKey).stream()
                .filter(s -> stepId.equals(s.getStepId()))
                .findFirst().orElseThrow();
    }

    protected void awaitStatus(String businessKey, ProcessStatus status) {
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(processOpt(businessKey).map(Process::getStatus)).contains(status));
    }

    protected List<OutboxMessageEntity> outboxMessages() {
        return outboxRepository.findAll();
    }
}
