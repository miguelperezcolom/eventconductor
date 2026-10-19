package io.mateu.workflow.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskDomainTest {

    @Test
    void aCallersGroupsCompareWithoutTheirPath() {
        var groups = new ArrayList<>(Arrays.asList("/finance", " ops ", "/", null));
        var caller = TaskCaller.of("carol", groups, false);

        assertThat(caller.groups()).containsExactlyInAnyOrder("finance", "ops");
        assertThat(caller.inAnyOf(List.of("//finance"))).isTrue();
        assertThat(caller.inAnyOf(Arrays.asList(null, "sales"))).isFalse();
        assertThat(caller.inAnyOf(null)).isFalse();
        assertThat(caller.known()).isTrue();
        assertThat(new TaskCaller(" ", null, false).known()).isFalse();
        assertThat(TaskCaller.of("x", null, false).groups()).isEmpty();
        assertThat(TaskCaller.anonymous().groups()).isEmpty();
        assertThat(new TaskCaller("x", Set.of("a"), true).admin()).isTrue();
    }

    @Test
    void aQueryDefaultsToTheOpenTasksMostUrgentFirst() {
        var query = new TaskQuery(null, null, null, null, null, " ", "", null, null, -3, 0);

        assertThat(query.scope()).isEqualTo(TaskQuery.Scope.ALL);
        assertThat(query.statuses()).containsExactly(FormExecutionStatus.PENDING, FormExecutionStatus.ASSIGNED);
        assertThat(query.sort()).isEqualTo(TaskQuery.Sort.PRIORITY);
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(TaskQuery.DEFAULT_SIZE);
        assertThat(query.processId()).isNull();
        assertThat(query.businessKey()).isNull();
        assertThat(new TaskQuery(null, null, null, null, null, null, null, " f ", null, 0, 10_000).size())
                .isEqualTo(TaskQuery.MAX_SIZE);
        assertThat(TaskQuery.open().withStatuses(List.of(FormExecutionStatus.COMPLETED)).statuses())
                .containsExactly(FormExecutionStatus.COMPLETED);
        assertThat(new TaskQuery(null, null, null, null, null, null, null, " f ", null, 0, 1).formId()).isEqualTo("f");
    }

    @Test
    void scopesAndSortsAreReadFromRequestParameters() {
        assertThat(TaskQuery.Scope.parse(null)).isEqualTo(TaskQuery.Scope.ALL);
        assertThat(TaskQuery.Scope.parse(" ")).isEqualTo(TaskQuery.Scope.ALL);
        assertThat(TaskQuery.Scope.parse("ALL")).isEqualTo(TaskQuery.Scope.ALL);
        assertThat(TaskQuery.Scope.parse("mine")).isEqualTo(TaskQuery.Scope.MINE);
        assertThat(TaskQuery.Scope.parse("claimable")).isEqualTo(TaskQuery.Scope.CANDIDATE);
        assertThat(TaskQuery.Scope.parse("candidate")).isEqualTo(TaskQuery.Scope.CANDIDATE);
        assertThat(TaskQuery.Scope.parse("my-groups")).isEqualTo(TaskQuery.Scope.GROUPS);
        assertThat(TaskQuery.Scope.parse("groups")).isEqualTo(TaskQuery.Scope.GROUPS);
        assertThat(TaskQuery.Scope.parse("mygroups")).isEqualTo(TaskQuery.Scope.GROUPS);
        assertThatThrownBy(() -> TaskQuery.Scope.parse("everyone")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TaskQuery.Sort.parse(null)).isEqualTo(TaskQuery.Sort.PRIORITY);
        assertThat(TaskQuery.Sort.parse(" ")).isEqualTo(TaskQuery.Sort.PRIORITY);
        assertThat(TaskQuery.Sort.parse("priority")).isEqualTo(TaskQuery.Sort.PRIORITY);
        assertThat(TaskQuery.Sort.parse("dueDate")).isEqualTo(TaskQuery.Sort.DUE);
        assertThat(TaskQuery.Sort.parse("due")).isEqualTo(TaskQuery.Sort.DUE);
        assertThat(TaskQuery.Sort.parse("created")).isEqualTo(TaskQuery.Sort.CREATED);
        assertThat(TaskQuery.Sort.parse("createdAt")).isEqualTo(TaskQuery.Sort.CREATED);
        assertThatThrownBy(() -> TaskQuery.Sort.parse("name")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTaskKnowsWhetherItIsOpenOverdueAndHowEscalated() {
        var now = LocalDateTime.of(2026, 10, 19, 12, 0);
        var task = FormExecution.builder().id("t").status(FormExecutionStatus.PENDING).dueDate(now.minusMinutes(1)).build();

        assertThat(task.isOverdue(now)).isTrue();
        assertThat(task.withStatus(FormExecutionStatus.COMPLETED).isOverdue(now)).isFalse();
        assertThat(task.withDueDate(null).isOverdue(now)).isFalse();
        assertThat(task.withDueDate(now.plusMinutes(1)).isOverdue(now)).isFalse();
        assertThat(task.priorityOrDefault()).isEqualTo(50);
        assertThat(task.escalations()).isZero();
        assertThat(task.withEscalationLevel(3).escalations()).isEqualTo(3);
        assertThat(task.isAssigned()).isFalse();
        assertThat(task.withUserId(" ").isAssigned()).isFalse();
        assertThat(task.candidateUsers()).isEmpty();
        assertThat(task.toString()).isEqualTo("t");
        assertThat(task.withId(null).toString()).isEqualTo("New form execution");
        assertThat(TaskPage.empty(TaskQuery.open()).items()).isEmpty();
        assertThat(new TaskPage(null, 0, 0, 1).items()).isEmpty();
    }
}
