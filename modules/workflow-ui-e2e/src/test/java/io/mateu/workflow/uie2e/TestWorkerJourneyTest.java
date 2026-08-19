package io.mateu.workflow.uie2e;

import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.application.ScenarioResolver;
import io.mateu.testworker.application.TaskOverrideStore;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.ReceivedTask;
import io.mateu.testworker.domain.ScenarioSource;
import io.mateu.testworker.domain.TaskOverride;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.uie2e.pages.TestWorkerUi;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UI-WORKER — the test worker's own pages, in a browser.
 *
 * <p>Everything else about this worker is checked without one: its scenario engine by unit tests,
 * and DIST-13 drives it end to end over real Kafka. What none of that can see is the half a person
 * uses to work out why a run went the way it did — whether the list shows the task the worker
 * recorded, whether the detail says which source answered it, and whether the page that changes
 * what happens next is actually offered.
 *
 * <p>The claims here are deliberately about <b>what the page shows and what it lets you do</b>,
 * not about filling in Mateu's create form. Driving that form would be testing Mateu's rendering
 * rather than this worker, and it is the part most likely to break for reasons that have nothing
 * to do with the product.
 */
class TestWorkerJourneyTest extends AbstractUiE2eTest {

    @Autowired
    ReceivedTaskStore receivedTasks;

    @Autowired
    TaskOverrideStore taskOverrides;

    @Autowired
    ScenarioResolver resolver;

    private TestWorkerUi worker;

    @BeforeEach
    void openTheWorkerUi() {
        worker = new TestWorkerUi(page);
    }

    @Test
    void the_list_shows_a_task_the_worker_recorded_and_the_detail_says_who_answered_it() {
        receivedTasks.save(recorded("exec-ui-1", "charge-card", ScenarioSource.TEST_CONFIG,
                Outcome.ERROR, "card declined"));

        worker.goToReceivedTasks().openRow("exec-ui-1");

        // The fields people actually come to this page for. `source` most of all: when a run
        // surprises you, the first question is whether the reply came from the scenario the test
        // wrote or from an override somebody left enabled, and this is where that is answered.
        assertThat(worker.text("charge-card")).isVisible();
        assertThat(worker.text("TEST_CONFIG")).isVisible();
        assertThat(worker.text("ERROR")).isVisible();
        assertThat(worker.text("card declined")).isVisible();
    }

    @Test
    void the_record_of_what_happened_cannot_be_created_from_the_ui_but_a_canned_reply_can() {
        receivedTasks.save(recorded("exec-ui-2", "notify", ScenarioSource.DEFAULT,
                Outcome.COMPLETED, null));

        worker.goToReceivedTasks();
        // No way to invent a task the worker was never given: history is a record, and a page that
        // lets you add to it is a page that lets you debug a run that never happened.
        assertThat(worker.button("New")).hasCount(0);

        worker.goToTaskOverrides();
        // Overrides are the opposite: changing what happens next is the whole point of the page.
        assertThat(worker.button("New")).hasCount(1);
    }

    @Test
    void an_override_on_the_page_is_the_rule_the_worker_will_apply() {
        taskOverrides.save(new TaskOverride(null, "slow charge-card down", "booking", "charge-card",
                null, true, 4_000L, Outcome.ERROR, "card declined", null, null, false,
                List.of(), List.of()));

        worker.goToTaskOverrides();
        assertThat(worker.text("slow charge-card down")).isVisible();

        // …and it is not just a row in a grid. The same store the page renders is the one the
        // resolver reads, so what is on screen is what the next task on that step will do. Without
        // this the page could be a faithful view of a table nothing consults.
        var resolved = resolver.resolve(new TaskExecutionRequested(
                "exec-ui-3", "process-ui", "booking", "charge-card", "", List.of()));

        org.assertj.core.api.Assertions.assertThat(resolved.source()).isEqualTo(ScenarioSource.OVERRIDE);
        org.assertj.core.api.Assertions.assertThat(resolved.matchedBy()).isEqualTo("slow charge-card down");
        org.assertj.core.api.Assertions.assertThat(resolved.scenario().durationMs()).isEqualTo(4_000);
        org.assertj.core.api.Assertions.assertThat(resolved.scenario().outcome()).isEqualTo(Outcome.ERROR);
    }

    private static ReceivedTask recorded(String id, String stepId, ScenarioSource source,
                                         Outcome outcome, String note) {
        // taskId is empty, as it is on every ACTION step the engine dispatches — the step id is
        // what identifies the task, and it is what the page has to show.
        return new ReceivedTask(id, "process-ui", "booking", stepId, "", LocalDateTime.now(), 1,
                source, stepId, outcome, 250L, LocalDateTime.now(), note, List.of(), "{}");
    }
}
