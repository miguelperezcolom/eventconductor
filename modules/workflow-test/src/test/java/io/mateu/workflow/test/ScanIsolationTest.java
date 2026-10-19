package io.mateu.workflow.test;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An ordinary embedded application's test — one that does not use {@link EventConductorTest} —
 * scans {@code io.mateu.workflow}, the kit's package included, and must get none of the kit.
 */
@SpringBootTest(classes = ScanIsolationTest.App.class, properties = {
        "workflow.mode=embedded", "workflow.persistence=memory", "spring.main.web-application-type=none"})
class ScanIsolationTest {

    @WorkflowEmbeddedApplication
    static class App {
    }

    @Autowired
    ApplicationContext context;

    @Test
    void theKitIsNotPickedUpByAComponentScan() {
        assertThat(context.getBeanNamesForType(WorkflowTestRuntime.class)).isEmpty();
        assertThat(context.getBeanNamesForType(StubbingTaskExecutor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TestClock.class)).isEmpty();
        assertThat(context.getBeanNamesForType(WorkflowTestApplication.class)).isEmpty();
        assertThat(EngineClock.get()).isNotInstanceOf(TestClock.class);
    }
}
