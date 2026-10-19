package io.mateu.workflow.test;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.domain.aggregates.Escalation;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migrating a process whose human task has already escalated (2.31): the levels that fired are kept (by
 * position), and the next one is re-armed from the target's levels, counted from when the task was created.
 * A target that drops a level that fired is refused.
 */
@EventConductorTest
class MigrationEscalationTest {

    @Autowired
    WorkflowTestRuntime runtime;
    @Autowired
    WorkflowDefinitionRepository definitions;
    @Autowired
    ProcessOperationsService operations;

    private static StepExecution approve(TestProcess process) {
        return process.stepExecutions("approve").getFirst();
    }

    private WorkflowDefinition withEscalations(WorkflowDefinition v1, List<Escalation> levels) {
        return v1.withSteps(v1.steps().stream()
                .map(step -> "approve".equals(step.id()) ? step.withEscalation(levels) : step).toList())
                .withVersion(v1.version() + 1);
    }

    @Test
    void firedLevelsAreKeptAndTheNextOneIsReArmedFromTheTarget() {
        var v1 = definitions.findById("expense-approval").orElseThrow();
        try {
            var expense = runtime.start("expense-approval", "mig-expense-1",
                    Map.of("manager", "bob", "region", "emea", "amount", 200, "expenseId", "E-9"));
            runtime.advanceTime(Duration.ofHours(48));
            var before = approve(expense);
            assertThat(before.escalationsFired()).isEqualTo(1);

            // A target with no escalations would forget a level that fired: refused.
            definitions.save(withEscalations(v1, List.of()));
            assertThatThrownBy(() -> operations.migrate(expense.id(), null, Map.of(), Map.of(), "drop them", null))
                    .isInstanceOf(ProcessOperationsService.ProcessStateConflictException.class)
                    .hasMessageContaining("already fired 1 escalation level(s)");

            // v2 keeps level 1 and escalates to the director after 3 days instead of notifying after 4.
            var levels = v1.steps().stream().filter(s -> "approve".equals(s.id())).findFirst().orElseThrow().escalations();
            definitions.save(withEscalations(v1, List.of(levels.get(0),
                    new Escalation("P3D", null, null, "director-review", null))));
            operations.migrate(expense.id(), null, Map.of(), Map.of(), "escalate sooner", null);
            runtime.runUntilIdle();

            var after = approve(expense);
            assertThat(after.getId()).isEqualTo(before.getId());
            assertThat(after.escalationsFired()).isEqualTo(1);
            assertThat(after.getDeadlineAt()).isEqualTo(after.getStartedAt().plusDays(3));

            runtime.advanceTime(Duration.ofHours(23));
            assertThat(expense).isWaitingAt("approve");
            runtime.advanceTime(Duration.ofHours(1));
            assertThat(expense).hasStepStatus("approve", StepExecutionStatus.TIMEOUT).isWaitingAt("director-review");
        } finally {
            definitions.save(v1);
        }
    }
}
