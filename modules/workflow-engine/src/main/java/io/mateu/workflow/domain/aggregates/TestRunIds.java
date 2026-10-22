package io.mateu.workflow.domain.aggregates;

import java.util.regex.Pattern;

/**
 * The private definition ids IDE test runs execute under: {@code vscode-<runId>}, or
 * {@code vscode-<id>-<runId>} for a named document. Every run mints a new one, so anything keyed
 * by definition id with a bounded vocabulary — metric tags, for one — must fold them together.
 */
public final class TestRunIds {
    /** The one metric tag value every test run shares. */
    public static final String METRIC_TAG = "vscode-test-run";

    private static final Pattern ID = Pattern.compile(
            "vscode-(?:.+-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private TestRunIds() {}

    public static String of(String documentId, String runId) {
        String own = "vscode-" + runId;
        return documentId == null || documentId.isBlank() || documentId.equals(own) ? own : "vscode-" + documentId + "-" + runId;
    }

    public static boolean isTestRun(String definitionId) {
        return definitionId != null && ID.matcher(definitionId).matches();
    }
}
