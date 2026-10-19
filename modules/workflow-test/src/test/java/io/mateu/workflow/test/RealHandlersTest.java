package io.mateu.workflow.test;

import io.mateu.workflow.worker.api.TaskRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An application's own TaskHandler answers whatever no mock does; a definition can come from a file;
 * and a mock always wins over the handler.
 */
@EventConductorTest(classes = {WorkflowTestApplication.class, RealHandlersTest.Handlers.class},
        definitions = "src/test/flows/greeting.json")
class RealHandlersTest {

    record Guest(String name) {
    }

    record Greeting(String greeting) {
    }

    @TestConfiguration
    static class Handlers {
        @Bean
        TaskRegistration<Guest, Greeting> greet() {
            return new TaskRegistration<>("greet", 1, null, Guest.class, Greeting.class,
                    (guest, context) -> new Greeting("Hello, " + guest.name()));
        }
    }

    @Test
    void theRealHandlerRunsWhenNothingIsMocked(WorkflowTestRuntime runtime) {
        var greeting = runtime.start("greeting", "greet-1", Map.of("name", "Ann"));
        assertThat(greeting).isCompleted().hasVariable("greeting", "Hello, Ann");
    }

    @Test
    void aMockWinsOverTheHandler(WorkflowTestRuntime runtime) {
        runtime.whenTask("greet").thenComplete(Map.of("greeting", "mocked"));
        var greeting = runtime.start("greeting", "greet-2", Map.of("name", "Bo"));
        assertThat(greeting).isCompleted().hasVariable("greeting", "mocked");
    }

    @Test
    void definitionsCanBeDeployedAtRunTime(WorkflowTestRuntime runtime) {
        runtime.deploy("src/test/flows");
        assertThatThrownBy(() -> runtime.deploy("src/test/flows/missing.json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.deploy("classpath:nothing-here/at-all.json"))
                .isInstanceOf(IllegalArgumentException.class);
        runtime.deploy("classpath:workflows/quote.json");
    }

    @Test
    void anAnswerlessMockIsAnError(WorkflowTestRuntime runtime) {
        runtime.whenTask("greet");
        assertThat(runtime.start("greeting", "greet-3", Map.of("name", "Cy"))).isFailed();
    }
}
