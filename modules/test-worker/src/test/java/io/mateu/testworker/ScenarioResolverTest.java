package io.mateu.testworker;

import io.mateu.workflow.dtos.Variable;
import io.mateu.testworker.application.ScenarioNotReadableException;
import io.mateu.testworker.application.ScenarioResolver;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.ScenarioSource;
import io.mateu.testworker.domain.TaskOverride;
import io.mateu.testworker.infra.out.persistence.InMemoryTaskOverrideStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.mateu.testworker.Tasks.task;
import static io.mateu.testworker.Tasks.testConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which of the three places that can describe a task actually gets to. */
class ScenarioResolverTest {

    private InMemoryTaskOverrideStore overrides;
    private ScenarioResolver resolver;

    @BeforeEach
    void setUp() {
        overrides = new InMemoryTaskOverrideStore();
        resolver = new ScenarioResolver(overrides, Duration.ofSeconds(2));
    }

    @Test
    void a_task_nothing_describes_takes_the_configured_time_and_completes() {
        var resolved = resolver.resolve(task("charge-card"));

        assertThat(resolved.source()).isEqualTo(ScenarioSource.DEFAULT);
        assertThat(resolved.scenario().outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(resolved.scenario().duration()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void the_named_entry_wins_over_the_default_block_and_inherits_what_it_does_not_say() {
        var resolved = resolver.resolve(task("charge-card", testConfig("""
                {
                  "default": { "durationMs": 50, "outcome": "COMPLETED" },
                  "tasks": { "charge-card": { "outcome": "ERROR", "reason": "card declined" } }
                }
                """)));

        assertThat(resolved.source()).isEqualTo(ScenarioSource.TEST_CONFIG);
        assertThat(resolved.matchedBy()).isEqualTo("charge-card");
        assertThat(resolved.scenario().outcome()).isEqualTo(Outcome.ERROR);
        assertThat(resolved.scenario().reason()).isEqualTo("card declined");
        // Inherited from the default block, which is the point of stating only what differs.
        assertThat(resolved.scenario().durationMs()).isEqualTo(50);
    }

    @Test
    void a_task_the_config_does_not_name_still_gets_the_default_block() {
        var resolved = resolver.resolve(task("notify", testConfig("""
                { "default": { "durationMs": 10 } }
                """)));

        assertThat(resolved.source()).isEqualTo(ScenarioSource.TEST_CONFIG);
        assertThat(resolved.matchedBy()).isNull();
        assertThat(resolved.scenario().durationMs()).isEqualTo(10);
        assertThat(resolved.scenario().outcome()).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    void a_key_may_name_the_step_instead_of_the_task() {
        var resolved = resolver.resolve(task("charge-card", testConfig("""
                { "tasks": { "step-charge-card": { "durationMs": 7 } } }
                """)));

        assertThat(resolved.matchedBy()).isEqualTo("step-charge-card");
        assertThat(resolved.scenario().durationMs()).isEqualTo(7);
    }

    @Test
    void test_config_wins_over_an_override_that_would_otherwise_match() {
        overrides.save(anOverride("slow it down", 9_000L, Outcome.ERROR));

        var resolved = resolver.resolve(task("charge-card", testConfig("""
                { "tasks": { "charge-card": { "durationMs": 5 } } }
                """)));

        assertThat(resolved.source()).isEqualTo(ScenarioSource.TEST_CONFIG);
        assertThat(resolved.scenario().durationMs()).isEqualTo(5);
        assertThat(resolved.scenario().outcome()).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    void an_override_answers_a_process_that_states_nothing() {
        overrides.save(anOverride("slow it down", 9_000L, Outcome.ERROR));

        var resolved = resolver.resolve(task("charge-card"));

        assertThat(resolved.source()).isEqualTo(ScenarioSource.OVERRIDE);
        assertThat(resolved.matchedBy()).isEqualTo("slow it down");
        assertThat(resolved.scenario().durationMs()).isEqualTo(9_000);
        assertThat(resolved.scenario().outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    void the_most_specific_override_wins_so_a_blanket_row_never_shadows_a_precise_one() {
        overrides.save(new TaskOverride(null, "everything", null, null, null, true,
                1_000L, Outcome.COMPLETED, null, null, null, false, List.of(), List.of()));
        overrides.save(new TaskOverride(null, "just this task", "booking", null, "charge-card",
                true, 20L, Outcome.ERROR, null, null, null, false, List.of(), List.of()));

        var resolved = resolver.resolve(task("charge-card"));

        assertThat(resolved.matchedBy()).isEqualTo("just this task");
        assertThat(resolved.scenario().durationMs()).isEqualTo(20);
    }

    @Test
    void a_disabled_override_is_not_matched() {
        overrides.save(new TaskOverride(null, "off", "booking", null, "charge-card", false,
                20L, Outcome.ERROR, null, null, null, false, List.of(), List.of()));

        assertThat(resolver.resolve(task("charge-card")).source()).isEqualTo(ScenarioSource.DEFAULT);
    }

    @Test
    void an_override_only_changes_what_it_states() {
        overrides.save(new TaskOverride(null, "slower", "booking", null, "charge-card", true,
                45L, null, null, null, null, false, List.of(), List.of()));

        var resolved = resolver.resolve(task("charge-card"));

        assertThat(resolved.scenario().durationMs()).isEqualTo(45);
        assertThat(resolved.scenario().outcome()).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    void a_scenario_that_cannot_be_read_is_an_error_rather_than_a_silent_default() {
        assertThatThrownBy(() -> resolver.resolve(task("charge-card", testConfig("{ not json"))))
                .isInstanceOf(ScenarioNotReadableException.class)
                .hasMessageContaining("TEST_CONFIG could not be read");
    }

    @Test
    void a_misspelled_property_is_rejected_rather_than_ignored() {
        // The whole value of a strict reader: "durationMS" silently meaning "two seconds" turns a
        // test that proves nothing into a test that looks like it passed.
        assertThatThrownBy(() -> resolver.resolve(task("charge-card", testConfig("""
                { "tasks": { "charge-card": { "durationMS": 5 } } }
                """))))
                .isInstanceOf(ScenarioNotReadableException.class);
    }

    @Test
    void the_variable_name_is_matched_without_regard_to_case() {
        var resolved = resolver.resolve(task("charge-card",
                new Variable("test_config", "{ \"default\": { \"durationMs\": 3 } }")));

        assertThat(resolved.source()).isEqualTo(ScenarioSource.TEST_CONFIG);
        assertThat(resolved.scenario().durationMs()).isEqualTo(3);
    }

    @Test
    void an_empty_variable_reads_as_no_scenario_at_all() {
        var resolved = resolver.resolve(task("charge-card", testConfig("   ")));

        assertThat(resolved.source()).isEqualTo(ScenarioSource.DEFAULT);
    }

    @Test
    void an_outcome_is_read_whatever_case_it_is_written_in() {
        var resolved = resolver.resolve(task("charge-card", testConfig("""
                { "tasks": { "charge-card": { "outcome": "no_reply" } } }
                """)));

        assertThat(resolved.scenario().outcome()).isEqualTo(Outcome.NO_REPLY);
    }

    private TaskOverride anOverride(String name, Long durationMs, Outcome outcome) {
        return new TaskOverride(null, name, "booking", null, "charge-card", true,
                durationMs, outcome, null, null, null, false, List.of(), List.of());
    }
}
