package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REPLY DSL at the model boundary: REPLY steps and the definition-level syncInvocation survive
 * the JSON round trip, the invariants enforce the reply rules, and a process replies once.
 */
class ReplyDslRoundTripTest {

    private static Step step(String id, StepType type, String precondition) {
        return new Step(id, "wd-1", type, id, null, precondition, null, null, false, null, null, null,
                null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private static WorkflowDefinition definition(Step... steps) {
        return new WorkflowDefinition("wd-1", "Sync", 1, null, false, 0, false, null, 0, List.of(steps));
    }

    @Test
    void a_reply_step_round_trips_its_fields() {
        var reply = step("reply", StepType.REPLY, "start").withReplyVariables(List.of("bookingId"));
        var back = JsonSerializer.pojoFromJson(JsonSerializer.toJson(reply), Step.class);
        assertThat(back.type()).isEqualTo(StepType.REPLY);
        assertThat(back.replyVariables()).containsExactly("bookingId");

        var expression = step("reply", StepType.REPLY, "start").withReplyExpression("{'ok': true}");
        assertThat(JsonSerializer.pojoFromJson(JsonSerializer.toJson(expression), Step.class).replyExpression())
                .isEqualTo("{'ok': true}");
    }

    @Test
    void sync_invocation_round_trips_and_defaults_its_policies() {
        var def = definition(step("start", StepType.START, null), step("reply", StepType.REPLY, "start"))
                .withSyncInvocation(new SyncInvocation(true, null, null, 0));
        var back = JsonSerializer.pojoFromJson(JsonSerializer.toJson(def), WorkflowDefinition.class);
        assertThat(back.syncInvocation().enabled()).isTrue();
        assertThat(back.syncInvocation().onFailure()).isEqualTo(SyncInvocation.FailurePolicy.REPLY_IMMEDIATELY);
        assertThat(back.syncInvocation().onLockBusy()).isEqualTo(SyncInvocation.LockBusyPolicy.WAIT);
        assertThat(back.isSyncInvocable()).isTrue();
    }

    @Test
    void every_copy_keeps_the_sync_invocation() {
        var sync = new SyncInvocation(true, SyncInvocation.FailurePolicy.REPLY_AFTER_COMPENSATION,
                SyncInvocation.LockBusyPolicy.FAIL, 3000);
        var def = definition(step("start", StepType.START, null), step("reply", StepType.REPLY, "start"))
                .withSyncInvocation(sync);
        assertThat(def.withPaused(true).syncInvocation()).isEqualTo(sync);
        assertThat(def.withSteps(def.steps()).syncInvocation()).isEqualTo(sync);
        assertThat(def.withMaxSteps(9).syncInvocation()).isEqualTo(sync);
        assertThat(def.withLayout(null).syncInvocation()).isEqualTo(sync);
        assertThat(def.withProcessLock(new ProcessLock(null, "k")).syncInvocation()).isEqualTo(sync);
        assertThat(def.withVersion(4).syncInvocation()).isEqualTo(sync);
        assertThat(def.withRuntimeStatus(WorkflowStatus.DISABLED).syncInvocation()).isEqualTo(sync);
        assertThat(def.withDeclaredStatus(WorkflowStatus.ACTIVE).syncInvocation()).isEqualTo(sync);
        assertThat(def.withRuntimeStateOf(def).syncInvocation()).isEqualTo(sync);
    }

    @Test
    void a_reply_step_declaring_both_sources_is_rejected() {
        var def = definition(step("start", StepType.START, null),
                step("reply", StepType.REPLY, "start").withReplyVariables(List.of("a")).withReplyExpression("b"));
        assertThatThrownBy(def::checkInvariants).hasMessageContaining("more than one of replyVariables");
    }

    @Test
    void two_replies_on_one_path_are_rejected() {
        var def = definition(step("start", StepType.START, null), step("r1", StepType.REPLY, "start"),
                step("r2", StepType.REPLY, "r1"));
        assertThatThrownBy(def::checkInvariants).hasMessageContaining("same path");
    }

    @Test
    void a_sync_invocable_definition_without_a_reply_is_rejected() {
        var def = definition(step("start", StepType.START, null), step("end", StepType.END, "start"))
                .withSyncInvocation(new SyncInvocation(true, null, null, 0));
        assertThatThrownBy(def::checkInvariants).hasMessageContaining("no REPLY step");
    }

    @Test
    void a_path_that_ends_without_replying_is_a_topology_warning() {
        var choice = step("choice", StepType.CHOICE, "start");
        var def = definition(step("start", StepType.START, null), choice,
                step("reply", StepType.REPLY, "choice"), step("endOk", StepType.END, "reply"),
                step("endSilent", StepType.END, "choice"))
                .withSyncInvocation(new SyncInvocation(true, null, null, 0));
        def.checkInvariants();
        assertThat(def.topologyWarnings()).anyMatch(w -> w.contains("endSilent"));
    }

    @Test
    void a_process_replies_once() {
        var process = Process.builder().id("p").variables(List.of()).build();
        assertThat(process.hasReplied()).isFalse();
        assertThat(process.recordReply(ProcessReply.replied("r1", "{\"a\":1}"))).isTrue();
        assertThat(process.recordReply(ProcessReply.replied("r2", "{\"a\":2}"))).isFalse();
        assertThat(process.getReply().stepId()).isEqualTo("r1");
        assertThat(process.getReply().isFailure()).isFalse();
        assertThat(ProcessReply.of(ProcessReply.Outcome.FAILED, null, "boom").compensation())
                .isEqualTo(ProcessReply.Compensation.NONE);
        assertThat(ProcessReply.of(ProcessReply.Outcome.FAILED, null, "boom").isFailure()).isTrue();
    }

    @Test
    void a_reply_template_round_trips_and_a_broken_one_is_rejected() {
        var reply = step("reply", StepType.REPLY, "start").withReplyTemplate(java.util.Map.of("id", "${bookingId}"));
        var back = JsonSerializer.pojoFromJson(JsonSerializer.toJson(reply), Step.class);
        assertThat(back.replyTemplate()).isEqualTo(java.util.Map.of("id", "${bookingId}"));

        var bad = definition(step("start", StepType.START, null),
                step("reply", StepType.REPLY, "start").withReplyTemplate(java.util.Map.of("id", "${bookingId +}")));
        assertThatThrownBy(bad::checkInvariants).hasMessageContaining("replyTemplate.id");
    }
}
