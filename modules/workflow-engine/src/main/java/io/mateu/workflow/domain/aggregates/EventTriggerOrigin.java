package io.mateu.workflow.domain.aggregates;

/**
 * Where an event trigger is declared, which decides who owns it.
 *
 * <ul>
 *   <li>{@link #DEFINITION} — an entry of the definition's {@code triggers}: owned by the definition
 *       file, changed there.</li>
 *   <li>{@link #EVENTS_FILE} — an entry of an {@code events.yaml} imported from the classpath, a
 *       directory or a git repository, naming the workflow it starts: owned by that file.</li>
 * </ul>
 *
 * <p>Wherever it is declared, a trigger is operated the same way: paused and resumed by
 * {@code <workflow>/<trigger>} on Workflow → Event triggers, the REST API and MCP.
 */
public enum EventTriggerOrigin {
    DEFINITION,
    EVENTS_FILE;

    /** "Definition", or "events.yaml (git)", "events.yaml (directory)", "events.yaml (classpath)". */
    public String label(String source) {
        return switch (this) {
            case DEFINITION -> "Definition";
            case EVENTS_FILE -> "events.yaml (" + Schedule.cronFileSourceKind(source) + ")";
        };
    }
}
