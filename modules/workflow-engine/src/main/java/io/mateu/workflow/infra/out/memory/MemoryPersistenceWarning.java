package io.mateu.workflow.infra.out.memory;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Logs a loud warning at startup when the engine runs with {@code workflow.persistence=memory} —
 * the default. In-memory persistence keeps all process, step, log and outbox state in the JVM
 * heap: a restart or a crash loses every running process with no recovery. That is fine for tests,
 * demos and short-lived embedded runs, and a data-loss trap for anything durable.
 *
 * <p>Booting a non-durable store silently is how a deployment ends up "losing" processes nobody
 * knew were volatile. This makes the choice visible in the log every time, so it is deliberate
 * rather than a forgotten default. Set {@code workflow.persistence=jpa} for durable state.
 */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
@Slf4j
public class MemoryPersistenceWarning {

    @PostConstruct
    void warn() {
        log.warn("workflow.persistence=memory: process state is held in the JVM heap and is NOT "
                + "durable — a restart or crash loses every running process. Use "
                + "workflow.persistence=jpa for durable state in production.");
    }
}
