package io.mateu.workflow;

import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.application.SimulatedTaskHandler;
import io.mateu.testworker.application.TaskOverrideStore;
import io.mateu.testworker.infra.in.ui.pages.ReceivedTasks;
import io.mateu.testworker.infra.in.ui.pages.TaskOverrides;
import io.mateu.testworker.infra.out.persistence.JpaReceivedTaskStore;
import io.mateu.testworker.infra.out.persistence.JpaTaskOverrideStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That this application is wired the way it claims to be.
 *
 * <p>A bare {@code contextLoads} would pass on an application that started and did nothing, which
 * is most of what could go wrong here: the binding is declared in YAML, the stores are chosen by a
 * property, and the pages are found by component scan — three things that fail silently by simply
 * not being there.
 */
@SpringBootTest
class AppApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void the_worker_listens_for_tasks_and_can_play_them() {
        assertThat(context.getBean("consumeWorkerEvent")).isInstanceOf(Function.class);
        assertThat(context.getBean(SimulatedTaskHandler.class)).isNotNull();
    }

    @Test
    void worker_persistence_jpa_selects_the_database_stores() {
        assertThat(context.getBean(ReceivedTaskStore.class)).isInstanceOf(JpaReceivedTaskStore.class);
        assertThat(context.getBean(TaskOverrideStore.class)).isInstanceOf(JpaTaskOverrideStore.class);
    }

    @Test
    void the_pages_that_edit_a_scenario_by_hand_are_registered() {
        assertThat(context.getBean(ReceivedTasks.class)).isNotNull();
        assertThat(context.getBean(TaskOverrides.class)).isNotNull();
    }

    @Test
    void the_engine_is_not_running_in_here() {
        // The whole arrangement depends on this: a worker that embedded the engine would be
        // testing itself, and every scenario it proved would prove nothing about a deployment.
        assertThat(context.getBeanNamesForType(Object.class))
                .noneMatch(name -> name.toLowerCase().contains("workfloworchestration"));
    }
}
