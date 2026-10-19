package io.mateu.workflowbench.suite.memory;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.workflowbench.suite.SuiteEngineBeans;
import org.springframework.context.annotation.Import;

/** The engine in embedded mode with in-memory persistence. One application per package — see SuiteJpaApp. */
@WorkflowEmbeddedApplication
@Import(SuiteEngineBeans.class)
public class SuiteMemoryApp {
}
