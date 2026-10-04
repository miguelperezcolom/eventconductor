package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.infra.in.ui.FormsRoutes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which task a route to the task page names. */
class TaskRouteTest {

    @Test
    void theIdIsTheSegmentAfterTheTaskPagesPrefix() {
        assertThat(Task.taskIdOf("/forms/task/977984b8-abd5")).isEqualTo("977984b8-abd5");
        assertThat(Task.taskIdOf("/forms/task/t-1?from=inbox")).isEqualTo("t-1");
        assertThat(Task.taskIdOf(FormsRoutes.task("t-2"))).isEqualTo("t-2");
    }

    @Test
    void aRouteThatIsNotTheTaskPageNamesNoTask() {
        assertThat(Task.taskIdOf("/forms/tasks")).isNull();
        assertThat(Task.taskIdOf("/forms/task/")).isNull();
        assertThat(Task.taskIdOf("/forms/task/t-1/edit")).isNull();
        assertThat(Task.taskIdOf(null)).isNull();
    }
}
