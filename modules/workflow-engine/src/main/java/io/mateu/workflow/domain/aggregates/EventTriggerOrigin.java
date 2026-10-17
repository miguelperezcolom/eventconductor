package io.mateu.workflow.domain.aggregates;

/**
 * Where an event trigger is declared, which decides who owns it.
 *
 * <ul>
 *   <li>{@link #DEFINITION} — an entry of the definition's {@code triggers}: owned by the definition
 *       file, changed there.</li>
 *   <li>{@link #EVENTS_FILE} — an entry of an {@code events.yaml} imported from the classpath, a
 *       directory or a git repository, naming the workflow it starts: owned by that file.</li>
 *   <li>{@link #API} — created from the admin UI, the REST API or an MCP tool, and kept in the engine's
 *       store until a file declares the same {@code <workflow>/<trigger>}.</li>
 * </ul>
 *
 * <p>Only {@link #API} triggers are edited or deleted through the engine. A file-owned trigger is
 * changed by changing its file; through the engine it can only be paused and resumed. Wherever it is
 * declared, a trigger is operated the same way, by {@code <workflow>/<trigger>}.
 */
public enum EventTriggerOrigin {
    DEFINITION,
    EVENTS_FILE,
    API;

    /** "Definition", "events.yaml (git)", "events.yaml (directory)", "events.yaml (classpath)" or "UI/API". */
    public String label(String source) {
        return switch (this) {
            case DEFINITION -> "Definition";
            case EVENTS_FILE -> "events.yaml (" + Schedule.cronFileSourceKind(source) + ")";
            case API -> "UI/API";
        };
    }
}
