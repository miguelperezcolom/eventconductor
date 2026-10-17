package io.mateu.workflow.domain.aggregates;

/**
 * An event trigger read from an {@code events.yaml} file — or created through the engine (source
 * {@link #API_SOURCE}) — as the engine keeps it: which workflow it starts, the trigger itself, and
 * where it came from — the counterpart of {@link CronFileSchedule}.
 *
 * @param source     the source the file was imported from — {@code classpath:/workflows/events.yaml},
 *                   an imported directory's absolute path, a git repository url (the same key the
 *                   definition import prunes by) — or {@link #API_SOURCE} for one created from the UI,
 *                   the REST API or MCP. Re-importing a source replaces all its entries; the API
 *                   source is never imported into.
 * @param index      the entry's position in its source, for listing in the order it was written
 * @param workflowId the id of the workflow definition a matching event starts
 * @param trigger    destination, type, filter, business key and variables; its id as declared, or
 *                   null when the file declares none (derived from the type, see
 *                   {@code EventTriggerCatalog})
 */
public record EventFileTrigger(String source, int index, String workflowId, EventTrigger trigger) {

    /** The source key triggers created through the engine (UI, REST API, MCP) are stored under. */
    public static final String API_SOURCE = "ui";

    /** The key this trigger's pause is kept under, {@code <workflow>/<trigger>}; null without an id. */
    public String key() {
        return trigger == null || trigger.id() == null ? null : TriggerPause.keyOf(workflowId, trigger.id());
    }
}
