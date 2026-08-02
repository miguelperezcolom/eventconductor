package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.out.StepExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Arms the materialised deadline of steps that were already in flight when the engine was
 * upgraded to a version that stores it.
 *
 * <p>The scheduler now finds due work by querying the deadline column, so a step that started
 * under an older version — which has none — would wait forever: its TIMER would never fire and
 * its timeout would never expire. This runs one query at boot for exactly those steps and
 * rearms them from the state they already carry ({@code startedAt}, variables and step JSON),
 * which is all the deadline ever derived from.
 *
 * <p>Idempotent and self-healing: on an engine that has only ever run this version the query
 * matches nothing and this is a no-op, and it would recover from any future drift the same way.
 * The one full pass it does costs one query per process start, not one every scan tick — which
 * is the whole point of materialising the deadline in the first place.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StepDeadlineBackfillRunner implements ApplicationRunner {

    final StepExecutionRepository stepExecutionRepository;

    @Override
    public void run(ApplicationArguments args) {
        // withStartedAt recomputes the deadline; passing the unchanged value rearms the step
        // without moving its clock. Most live steps have no deadline at all (no timeout
        // configured) and recompute to null again — writing those back would mean rewriting
        // them on every boot for nothing, so only the ones that actually gained a deadline
        // are saved.
        var armed = stepExecutionRepository.findLiveWithoutDeadline().stream()
                .map(stepExecution -> stepExecution.withStartedAt(stepExecution.getStartedAt()))
                .filter(stepExecution -> stepExecution.getDeadlineAt() != null)
                .toList();
        armed.forEach(stepExecutionRepository::save);
        if (!armed.isEmpty()) {
            log.info("Armed the deadline of {} step execution(s) that were in flight before this upgrade",
                    armed.size());
        }
    }
}
