package io.mateu.workflow.autoconfigure;

import org.springframework.context.annotation.ComponentScan;

/**
 * What {@link EnableWorkflowUi} imports: a scan of the engines' UI layer, and nothing else.
 *
 * <p>Deliberately not a {@code @Configuration}: it lives under {@code io.mateu.workflow}, which the
 * embedded annotations scan, and a stereotype here would be picked up by that scan and mount the UI
 * in every embedded application — the very thing their exclusion is there to prevent. Imported, it
 * is processed as configuration all the same.
 */
@ComponentScan(basePackages = "io.mateu.workflow.infra.in.ui")
public class WorkflowUiConfiguration {
}
