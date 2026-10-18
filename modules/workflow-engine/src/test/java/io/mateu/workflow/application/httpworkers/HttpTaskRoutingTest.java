package io.mateu.workflow.application.httpworkers;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTaskRoutingTest {

    @Test
    void listedTopicsAreRoutedAndTheRestAreNot() {
        var routing = new HttpTaskRouting(List.of("payments", " downstream ", ""));
        assertThat(routing.routesTask("payments", "")).isTrue();
        assertThat(routing.routesTask(null, "")).isTrue(); // a step with no topic is on downstream
        assertThat(routing.routesTask("shipping", "")).isFalse();
        assertThat(routing.namesTopic("payments")).isTrue();
        assertThat(routing.namesTopic("shipping")).isFalse();
        assertThat(routing.routesAnything()).isTrue();
        assertThat(routing.toString()).contains("payments", "downstream");
    }

    @Test
    void aWildcardRoutesEverythingButTheBuiltInTasks() {
        var routing = new HttpTaskRouting(List.of("*"));
        assertThat(routing.routesTask("payments", "")).isTrue();
        assertThat(routing.routesTask("payments", "charge@1")).isTrue();
        assertThat(routing.routesTask(null, "complete-form")).isFalse();
        assertThat(routing.routesTask(null, "evaluate-rule")).isFalse();
        assertThat(routing.routesTask("http-calls", "http-call@1")).isFalse();
        assertThat(routing.routesTask("llm-calls", "")).isFalse();
        assertThat(routing.routesTask("payments", null)).isTrue();
        assertThat(routing.namesTopic("payments")).isFalse();
        assertThat(routing.toString()).isEqualTo("*");
    }

    @Test
    void namingABuiltInTopicRoutesItEvenWithTheWildcard() {
        var routing = new HttpTaskRouting(List.of("*", "http-calls"));
        assertThat(routing.routesTask("http-calls", "http-call@1")).isTrue();
        assertThat(routing.toString()).startsWith("* + ");
    }

    @Test
    void nothingListedRoutesNothing() {
        assertThat(new HttpTaskRouting(null).routesAnything()).isFalse();
        assertThat(new HttpTaskRouting(Arrays.asList((String) null)).routesTask("payments", "")).isFalse();
    }
}
