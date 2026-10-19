package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may see, claim, complete, release and reassign a task — the rule every inbox applies. */
class TaskVisibilityTest {

    static final TaskCaller ALICE = TaskCaller.of("alice", List.of(), false);
    static final TaskCaller CAROL = TaskCaller.of("carol", List.of("/finance"), false);
    static final TaskCaller MALLORY = TaskCaller.of("mallory", List.of("sales"), false);
    static final TaskCaller ADMIN = TaskCaller.of("root", List.of(), true);
    static final TaskCaller NOBODY = TaskCaller.anonymous();

    static FormExecution.FormExecutionBuilder task() {
        return FormExecution.builder().id("t").formId("f").status(FormExecutionStatus.PENDING)
                .variables(List.of()).values(List.of());
    }

    @Test
    void aTaskThatNamesNobodyIsAnybodysUntilSomebodyHoldsIt() {
        var open = task().build();
        assertThat(TaskVisibility.canSee(open, NOBODY)).isTrue();
        assertThat(TaskVisibility.canSee(open, MALLORY)).isTrue();
        assertThat(TaskVisibility.canClaim(open, MALLORY)).isTrue();
        // Somebody must be signed in to take it.
        assertThat(TaskVisibility.canClaim(open, NOBODY)).isFalse();

        var held = open.withUserId("alice").withStatus(FormExecutionStatus.ASSIGNED);
        assertThat(TaskVisibility.canSee(held, ALICE)).isTrue();
        assertThat(TaskVisibility.canSee(held, MALLORY)).isFalse();
        assertThat(TaskVisibility.canClaim(held, MALLORY)).isFalse();
        assertThat(TaskVisibility.canSee(held, ADMIN)).isTrue();
    }

    @Test
    void aTaskOfferedToCandidatesIsTheirsByNameOrByGroup() {
        var offered = task().candidateUsers(List.of("alice")).candidateGroups(List.of("finance")).restricted(true).build();

        assertThat(TaskVisibility.canSee(offered, ALICE)).isTrue();
        assertThat(TaskVisibility.canSee(offered, CAROL)).isTrue();
        assertThat(TaskVisibility.canSee(offered, MALLORY)).isFalse();
        assertThat(TaskVisibility.canSee(offered, NOBODY)).isFalse();
        assertThat(TaskVisibility.canClaim(offered, CAROL)).isTrue();
        assertThat(TaskVisibility.canClaim(offered, MALLORY)).isFalse();
        // A task admin sees and may claim every task, whoever it names.
        assertThat(TaskVisibility.canClaim(offered, ADMIN)).isTrue();
    }

    @Test
    void aRestrictedTaskThatNamesNobodyAnyMoreIsTheAdminsOnly() {
        // Assigned to one person who released it: still not everybody's business.
        var released = task().restricted(true).build();

        assertThat(TaskVisibility.canSee(released, ALICE)).isFalse();
        assertThat(TaskVisibility.canSee(released, ADMIN)).isTrue();
    }

    @Test
    void onlyTheHolderCompletesAndTheHolderOrAnAdminReleases() {
        var held = task().userId("alice").status(FormExecutionStatus.ASSIGNED).build();

        assertThat(TaskVisibility.canComplete(held, ALICE)).isTrue();
        assertThat(TaskVisibility.canComplete(held, ADMIN)).isFalse();
        assertThat(TaskVisibility.canRelease(held, ALICE)).isTrue();
        assertThat(TaskVisibility.canRelease(held, ADMIN)).isTrue();
        assertThat(TaskVisibility.canRelease(held, CAROL)).isFalse();
        assertThat(TaskVisibility.canRelease(task().build(), ADMIN)).isFalse();

        var done = held.withStatus(FormExecutionStatus.COMPLETED);
        assertThat(TaskVisibility.canComplete(done, ALICE)).isFalse();
        assertThat(TaskVisibility.canRelease(done, ALICE)).isFalse();
        assertThat(TaskVisibility.canClaim(done.withUserId(null), ALICE)).isFalse();
    }

    @Test
    void onlyAnAdminReassignsAndOnlyAnOpenTask() {
        assertThat(TaskVisibility.canReassign(task().build(), ADMIN)).isTrue();
        assertThat(TaskVisibility.canReassign(task().build(), ALICE)).isFalse();
        assertThat(TaskVisibility.canReassign(task().status(FormExecutionStatus.CANCELLED).build(), ADMIN)).isFalse();
    }

    @Test
    void theScopesAreMineClaimableAndMyGroups() {
        var mine = task().userId("alice").status(FormExecutionStatus.ASSIGNED).build();
        var byName = task().candidateUsers(List.of("alice")).restricted(true).build();
        var byGroup = task().candidateGroups(List.of("finance")).restricted(true).build();

        assertThat(TaskVisibility.inScope(mine, ALICE, TaskQuery.Scope.MINE)).isTrue();
        assertThat(TaskVisibility.inScope(byName, ALICE, TaskQuery.Scope.MINE)).isFalse();
        assertThat(TaskVisibility.inScope(byName, ALICE, TaskQuery.Scope.CANDIDATE)).isTrue();
        assertThat(TaskVisibility.inScope(byName, ALICE, TaskQuery.Scope.GROUPS)).isFalse();
        assertThat(TaskVisibility.inScope(byGroup, CAROL, TaskQuery.Scope.GROUPS)).isTrue();
        assertThat(TaskVisibility.inScope(byGroup, CAROL, TaskQuery.Scope.CANDIDATE)).isTrue();
        assertThat(TaskVisibility.inScope(byGroup, CAROL, TaskQuery.Scope.ALL)).isTrue();
        assertThat(TaskVisibility.inScope(byGroup.withUserId("dave"), CAROL, TaskQuery.Scope.GROUPS)).isFalse();
        assertThat(TaskVisibility.inScope(mine, NOBODY, TaskQuery.Scope.MINE)).isFalse();
    }
}
