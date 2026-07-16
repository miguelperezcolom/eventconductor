package io.mateu.workflow.e2e.support;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;

/**
 * Boots the engine in embedded mode for the e2e tests, excluding the UI layer.
 */
@WorkflowEmbeddedApplication
public class E2eTestApplication {
}
