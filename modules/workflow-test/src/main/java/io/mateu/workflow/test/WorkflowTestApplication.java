package io.mateu.workflow.test;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.test.context.TestComponent;

/**
 * The application {@link EventConductorTest} boots by default: the engine alone, embedded, with no
 * application beans. To run your own {@code TaskHandler}s behind un-mocked tasks, add your
 * configuration next to it — {@code @EventConductorTest(classes = {WorkflowTestApplication.class,
 * MyHandlers.class})} — or point {@code classes} at your own {@code @WorkflowEmbeddedApplication}.
 *
 * <p>{@code @TestComponent}: it lives under {@code io.mateu.workflow}, which every embedded
 * application scans, and must never become a second application configuration in a test context
 * that did not name it. (Not {@code @TestConfiguration}: Spring Boot would then still look for a
 * {@code @SpringBootConfiguration} of its own when this is the only class given.)
 */
@WorkflowEmbeddedApplication
@TestComponent
public class WorkflowTestApplication {
}
