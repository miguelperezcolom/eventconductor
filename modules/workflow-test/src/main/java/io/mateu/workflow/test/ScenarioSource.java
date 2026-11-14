package io.mateu.workflow.test;

import org.junit.jupiter.params.provider.ArgumentsSource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Feeds a {@code @ParameterizedTest} with the {@link Scenario}s under these locations — one invocation per
 * scenario, each a test of its own with fresh mocks:
 *
 * <pre>{@code
 * @EventConductorTest
 * class BookingScenariosTest {
 *
 *     @ParameterizedTest(name = "{0}")
 *     @ScenarioSource("src/test/scenarios")
 *     void runs(Scenario scenario, WorkflowTestRuntime runtime) {
 *         runtime.run(scenario);
 *     }
 * }
 * }</pre>
 *
 * Locations are files or directories, relative to the working directory (the module, under Maven), or
 * {@code classpath:} locations; see {@link Scenario#load(String...)}.
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ArgumentsSource(ScenarioArgumentsProvider.class)
public @interface ScenarioSource {

    /** Scenario files or directories of them. */
    String[] value();
}
