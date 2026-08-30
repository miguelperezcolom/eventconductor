package io.mateu.testworker;

import io.mateu.testworker.application.DefaultScenarioSeeder;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.TaskOverride;
import io.mateu.testworker.domain.TaskScenario;
import io.mateu.testworker.infra.out.persistence.InMemoryTaskOverrideStore;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultScenarioSeederTest {

    private final TaskScenario baseline = TaskScenario.baseline(Duration.ofSeconds(2));

    private TaskExecutionRequested task(String definitionId, String stepId) {
        return new TaskExecutionRequested("se-" + System.nanoTime(), "p-1", definitionId, stepId, "",
                List.of());
    }

    @Test
    void seedsAnEnabledRowThatReproducesTheBaselineAndMatchesFutureExecutions() {
        var store = new InMemoryTaskOverrideStore();
        var seeder = new DefaultScenarioSeeder(store);

        seeder.seedIfAbsent(task("book-flow", "verify-payment"), baseline);

        assertThat(store.findAll()).hasSize(1);
        var seeded = store.findAll().get(0);
        assertThat(seeded.enabled()).isTrue();
        assertThat(seeded.workflowDefinitionId()).isEqualTo("book-flow");
        assertThat(seeded.stepId()).isEqualTo("verify-payment");
        // Task id blank, so any future execution of the step matches — the whole point of the signature.
        assertThat(seeded.taskId()).isBlank();
        assertThat(seeded.matches("book-flow", "verify-payment", "se-9999")).isTrue();
        // The row reproduces the baseline, so nothing changes until it is edited.
        var reproduced = seeded.toScenario().withFallback(baseline);
        assertThat(reproduced.durationMs()).isEqualTo(baseline.durationMs());
        assertThat(reproduced.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(reproduced.failuresFirst()).isEqualTo(baseline.failuresFirst());
        assertThat(reproduced.replies()).isEqualTo(baseline.replies());
    }

    @Test
    void isIdempotentForTheSameSignature() {
        var store = new InMemoryTaskOverrideStore();
        var seeder = new DefaultScenarioSeeder(store);

        seeder.seedIfAbsent(task("book-flow", "verify-payment"), baseline);
        seeder.seedIfAbsent(task("book-flow", "verify-payment"), baseline);
        seeder.seedIfAbsent(task("book-flow", "verify-payment"), baseline);

        assertThat(store.findAll()).hasSize(1);
    }

    @Test
    void leavesAnExistingRowForTheSignatureAlone() {
        var store = new InMemoryTaskOverrideStore();
        // The user seeded it on an earlier run and then disabled it — it must not come back.
        store.save(new TaskOverride(null, "mine", "book-flow", "verify-payment", "", false,
                5_000L, Outcome.ERROR, "nope", 0, 1, false, List.of(), List.of()));

        // A fresh seeder (its run-local cache empty) still checks the store and finds it.
        new DefaultScenarioSeeder(store).seedIfAbsent(task("book-flow", "verify-payment"), baseline);

        assertThat(store.findAll()).hasSize(1);
        assertThat(store.findAll().get(0).enabled()).isFalse();
    }

    @Test
    void doesNothingWithoutASignatureToMatchOn() {
        var store = new InMemoryTaskOverrideStore();
        var seeder = new DefaultScenarioSeeder(store);

        seeder.seedIfAbsent(task("book-flow", ""), baseline);
        seeder.seedIfAbsent(task("", "verify-payment"), baseline);

        assertThat(store.findAll()).isEmpty();
    }
}
