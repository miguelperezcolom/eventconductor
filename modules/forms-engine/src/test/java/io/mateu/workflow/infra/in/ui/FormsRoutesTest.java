package io.mateu.workflow.infra.in.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormsRoutesTest {

    @Test
    void theTaskPageResolvesUnderTheRootMountOfAnEmbeddingConsoleToo() {
        var routes = new FormsRoutes().routes().stream()
                .map(entry -> entry.route() + " -> " + entry.viewModel())
                .toList();

        assertThat(routes).contains(
                "/forms/task/:_taskId -> io.mateu.workflow.infra.in.ui.pages.Task",
                "/forms/tasks -> io.mateu.workflow.infra.in.ui.pages.Tasks",
                "/_forms/forms/task/:_taskId -> io.mateu.workflow.infra.in.ui.pages.Task",
                "/_forms-admin/forms/task/:_taskId -> io.mateu.workflow.infra.in.ui.pages.Task");
    }
}
