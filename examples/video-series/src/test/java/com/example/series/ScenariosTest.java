package com.example.series;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.Scenario;
import io.mateu.workflow.test.ScenarioSource;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.params.ParameterizedTest;

/** Every scenario in src/test/scenarios (test cases as data), each one a test of its own. */
@EventConductorTest(properties = SeriesTests.NO_CRON)
class ScenariosTest {

    @ParameterizedTest(name = "{0}")
    @ScenarioSource("src/test/scenarios")
    void scenario(Scenario scenario, WorkflowTestRuntime runtime) {
        runtime.run(scenario);
    }
}
