package com.example.hotel;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.Scenario;
import io.mateu.workflow.test.ScenarioSource;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.params.ParameterizedTest;

/** Testing, part f — the scenarios in src/test/scenarios, each one a test. */
@EventConductorTest
class ScenariosTest {

    @ParameterizedTest(name = "{0}")
    @ScenarioSource("src/test/scenarios")
    void scenario(Scenario scenario, WorkflowTestRuntime runtime) {
        runtime.run(scenario);
    }
}
