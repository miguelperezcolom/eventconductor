package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lock DSL, pinned at the model boundary: LOCK/UNLOCK steps and a process-level lock survive the
 * JSON round-trip the parser and the persistence layer both rely on, and the domain invariant
 * rejects a lock step with no key.
 */
class LockDslRoundTripTest {

    @Test
    void a_lock_step_round_trips_its_lock_fields() {
        Step lock = new Step("lock-booking", "wd-1", StepType.LOCK, "Lock booking", null, "start",
                null, null, false, null, null, null, null, null, 0, null, null, null, null, 0, 0,
                false, null, 0, null)
                .withLockName("booking").withLockKey("bookingId");

        Step back = JsonSerializer.pojoFromJson(JsonSerializer.toJson(lock), Step.class);

        assertThat(back.type()).isEqualTo(StepType.LOCK);
        assertThat(back.lockName()).isEqualTo("booking");
        assertThat(back.lockKey()).isEqualTo("bookingId");
    }

    @Test
    void a_process_level_lock_round_trips() {
        var def = new WorkflowDefinition("wd-1", "With process lock", 1, null, false, 0, false, null,
                0, List.of()).withProcessLock(new ProcessLock("booking", "bookingId"));

        var back = JsonSerializer.pojoFromJson(JsonSerializer.toJson(def), WorkflowDefinition.class);

        assertThat(back.processLock()).isNotNull();
        assertThat(back.processLock().name()).isEqualTo("booking");
        assertThat(back.processLock().key()).isEqualTo("bookingId");
        assertThat(back.processLock().resolvedName("wd-1")).isEqualTo("booking");
    }

    @Test
    void a_process_lock_with_no_name_defaults_to_the_definition_id() {
        assertThat(new ProcessLock(null, "bookingId").resolvedName("wd-1")).isEqualTo("wd-1");
        assertThat(new ProcessLock("  ", "bookingId").resolvedName("wd-1")).isEqualTo("wd-1");
    }

    @Test
    void a_lock_step_without_a_key_is_rejected() {
        Step start = new Step("start", "wd-1", StepType.START, "Start", null, null, null, null, false,
                null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        Step badLock = new Step("lock", "wd-1", StepType.LOCK, "Lock", null, "start", null, null, false,
                null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var def = new WorkflowDefinition("wd-1", "Bad", 1, null, false, 0, false, null, 0,
                List.of(start, badLock));

        assertThatThrownBy(def::checkInvariants)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lockKey");
    }
}
