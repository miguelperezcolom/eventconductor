package io.mateu.testworker;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.testworker.domain.LogLine;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.ReceivedTask;
import io.mateu.testworker.domain.ScenarioSource;
import io.mateu.testworker.domain.TaskOverride;
import io.mateu.testworker.infra.out.persistence.JpaReceivedTaskStore;
import io.mateu.testworker.infra.out.persistence.JpaTaskOverrideStore;
import io.mateu.testworker.infra.out.persistence.ReceivedTaskEntityRepository;
import io.mateu.testworker.infra.out.persistence.TaskOverrideEntityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database stores, against a real schema.
 *
 * <p>Worth its own test because everything interesting here is a translation — enums to strings,
 * lists to JSON columns, and back — and a translation that loses something loses it silently. An
 * override whose variables vanish on the way to the database is a scenario that runs, passes, and
 * proves nothing.
 */
@DataJpaTest
@Import({JpaTaskOverrideStore.class, JpaReceivedTaskStore.class})
class JpaStoresTest {

    @Autowired
    TaskOverrideEntityRepository overrideEntities;

    @Autowired
    ReceivedTaskEntityRepository receivedTaskEntities;

    private JpaTaskOverrideStore overrides() {
        return new JpaTaskOverrideStore(overrideEntities);
    }

    private JpaReceivedTaskStore receivedTasks() {
        return new JpaReceivedTaskStore(receivedTaskEntities);
    }

    @Test
    void an_override_comes_back_with_its_variables_and_logs_intact() {
        var store = overrides();
        var id = store.save(new TaskOverride(null, "declined", "booking", null, "charge-card",
                true, 250L, Outcome.ERROR, "card declined", 2, 3, true,
                List.of(new Variable("seatId", "12A")),
                List.of(new LogLine(MessageType.Error, "no funds", 40L))));

        var stored = store.findById(id).orElseThrow();

        assertThat(stored.name()).isEqualTo("declined");
        assertThat(stored.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(stored.durationMs()).isEqualTo(250);
        assertThat(stored.failuresBeforeSuccess()).isEqualTo(2);
        assertThat(stored.replyTimes()).isEqualTo(3);
        assertThat(stored.ignoreCancellation()).isTrue();
        assertThat(stored.variables()).containsExactly(new Variable("seatId", "12A"));
        assertThat(stored.logs()).containsExactly(new LogLine(MessageType.Error, "no funds", 40L));
    }

    @Test
    void saving_an_override_twice_updates_it_rather_than_duplicating_it() {
        var store = overrides();
        var id = store.save(anOverride("first", true));

        store.save(new TaskOverride(id, "second", null, null, null, true, null, null, null,
                null, null, false, List.of(), List.of()));

        assertThat(store.findAll()).hasSize(1);
        assertThat(store.findById(id).orElseThrow().name()).isEqualTo("second");
    }

    @Test
    void only_enabled_overrides_are_offered_for_matching() {
        var store = overrides();
        store.save(anOverride("on", true));
        store.save(anOverride("off", false));

        assertThat(store.enabled()).extracting(TaskOverride::name).containsExactly("on");
        assertThat(store.findAll()).hasSize(2);
    }

    @Test
    void an_override_can_be_deleted() {
        var store = overrides();
        var id = store.save(anOverride("temporary", true));

        store.deleteAllById(List.of(id));

        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void a_received_task_comes_back_with_its_source_outcome_and_request_variables() {
        var store = receivedTasks();
        store.save(new ReceivedTask("exec-1", "process-1", "booking", "step-1", "charge-card",
                LocalDateTime.now(), 2, ScenarioSource.TEST_CONFIG, "charge-card", Outcome.ERROR,
                250L, LocalDateTime.now(), "failing attempt 2 of 3",
                List.of(new Variable("TEST_CONFIG", "{}")), "{\"durationMs\":250}"));

        var stored = store.findById("exec-1").orElseThrow();

        assertThat(stored.source()).isEqualTo(ScenarioSource.TEST_CONFIG);
        assertThat(stored.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(stored.attempt()).isEqualTo(2);
        assertThat(stored.note()).isEqualTo("failing attempt 2 of 3");
        assertThat(stored.requestVariables()).containsExactly(new Variable("TEST_CONFIG", "{}"));
        assertThat(stored.scenarioJson()).isEqualTo("{\"durationMs\":250}");
    }

    @Test
    void previous_deliveries_are_read_off_the_task_executions_own_row() {
        var store = receivedTasks();
        store.save(aTask("exec-1", "process-1", "step-charge"));

        assertThat(store.previousDeliveriesOf("exec-1")).isEqualTo(1);
        // A task execution nobody has seen is at zero, which is what makes the first attempt 1.
        assertThat(store.previousDeliveriesOf("never-seen")).isZero();
    }

    @Test
    void received_tasks_are_listed_newest_first() {
        var store = receivedTasks();
        var now = LocalDateTime.now();
        store.save(aTask("older", "process-1", "step-1").repliedWith(null, null, null));
        store.save(new ReceivedTask("newer", "process-1", "booking", "step-1", "charge-card",
                now.plusMinutes(1), 1, ScenarioSource.DEFAULT, null, null, 0L, null, null,
                List.of(), null));

        assertThat(store.findAll()).extracting(ReceivedTask::id).containsExactly("newer", "older");
    }

    private static TaskOverride anOverride(String name, boolean enabled) {
        return new TaskOverride(null, name, null, null, null, enabled, null, null, null,
                null, null, false, List.of(), List.of());
    }

    private static ReceivedTask aTask(String id, String processId, String stepId) {
        return new ReceivedTask(id, processId, "booking", stepId, "charge-card",
                LocalDateTime.now(), 1, ScenarioSource.DEFAULT, null, Outcome.COMPLETED, 0L,
                LocalDateTime.now(), null, List.of(), null);
    }
}
