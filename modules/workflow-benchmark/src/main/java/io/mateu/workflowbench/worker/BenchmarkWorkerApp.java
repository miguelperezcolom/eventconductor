package io.mateu.workflowbench.worker;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.WorkerReply;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.function.Consumer;

/**
 * A worker that mostly waits, but fails on demand so the reliability suite can exercise the retry,
 * saga-compensation and {@code COMPENSATION_FAILED} paths at scale.
 *
 * <p>Its think time is the dial that separates the two questions a throughput number usually
 * blurs together. At zero, what is left in the measurement is the engine. Turn it up and the
 * figure becomes what your workers can get through — which is the real ceiling in production, and
 * not the engine's to answer.
 *
 * <p><b>Deterministic failure by intent, not by chance.</b> Whether a task fails is decided by the
 * load driver, which tags a configured fraction of processes with a {@code benchOutcome} variable
 * ({@code ok} / {@code comp} / {@code compfail}). The worker is stateless and just honours the tag,
 * so the outcome of every process is knowable in advance — the reconciler predicts the terminal
 * status from the same intent (encoded in the business key) and asserts the engine agreed:
 * <ul>
 *   <li>{@code ok} — every step completes → process {@code COMPLETED}.</li>
 *   <li>{@code comp} — the {@code risky-*} step fails every attempt, its retries exhaust, the saga
 *       rolls back and the compensations succeed → process {@code COMPENSATED}.</li>
 *   <li>{@code compfail} — as above, but the {@code refund} compensation itself fails → process
 *       {@code COMPENSATION_FAILED} (the beta.023 path, otherwise never exercised under load).</li>
 * </ul>
 * Transient (retry-then-succeed) faults are provided by the chaos scenarios, not the worker — a
 * broker/DB/pod fault mid-task is exactly a transient failure, and modelling it here would need
 * per-attempt state a stateless worker cannot reliably keep.
 *
 * <p>It replies through {@link WorkerReply}, which echoes the process back so the event routes to
 * the pod that owns it — and, more to the point, refuses to let a reply be dropped. The first
 * version of this class sent the reply and ignored the result, and during a ninety-second broker
 * outage that quietly lost 3 352 of them. Every reply here — success or failure — goes through it.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class})
public class BenchmarkWorkerApp {

    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent(StreamBridge streamBridge) {
        var thinkMillis = Integer.getInteger("bench.worker.think-ms", 0);
        return event -> {
            if (!(event instanceof TaskExecutionRequested task)) {
                return;
            }
            if (!think(thinkMillis)) {
                return; // interrupted — leave the offset uncommitted so the task is redelivered
            }
            var outcome = variable(task.variables(), "benchOutcome");
            var step = task.stepId() == null ? "" : task.stepId();
            var wantsRollback = "comp".equals(outcome) || "compfail".equals(outcome);

            // Fail the designated risky step so its retries exhaust and the saga rolls back.
            if (wantsRollback && step.startsWith("risky")) {
                WorkerReply.failed(streamBridge, task, task.variables());
                return;
            }
            // Fail the first compensation too, to drive the process to COMPENSATION_FAILED.
            if ("compfail".equals(outcome) && "refund".equals(step)) {
                WorkerReply.failed(streamBridge, task, task.variables());
                return;
            }
            WorkerReply.completed(streamBridge, task, task.variables());
        };
    }

    /** @return false if interrupted while thinking (do not reply — let the task be redelivered). */
    private static boolean think(int millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String variable(List<Variable> variables, String name) {
        if (variables == null) {
            return null;
        }
        return variables.stream()
                .filter(v -> name.equals(v.name()))
                .map(Variable::value)
                .findFirst()
                .orElse(null);
    }
}
