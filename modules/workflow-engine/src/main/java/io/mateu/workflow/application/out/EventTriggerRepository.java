package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.EventFileTrigger;

import java.util.Collection;
import java.util.List;

/**
 * The event triggers declared outside the definitions — the entries of every imported
 * {@code events.yaml}, by the source they came from, and the triggers created through the engine
 * (source {@link EventFileTrigger#API_SOURCE}). A definition's own {@code triggers} ride on its row;
 * this holds the rest, so every pod matches inbound events against the same set.
 */
public interface EventTriggerRepository {

    /** Every stored entry, by source then index. */
    List<EventFileTrigger> findAll();

    /**
     * Replaces the entries of {@code source} with {@code triggers} (empty or null clears the source).
     *
     * @throws IllegalArgumentException for {@link EventFileTrigger#API_SOURCE}, which no import replaces
     */
    void replace(String source, List<EventFileTrigger> triggers);

    /**
     * Stores a trigger created through the engine, keyed by {@code <workflow>/<trigger>}: saving the same
     * key again (an edit) overwrites it. Its source is {@link EventFileTrigger#API_SOURCE}.
     */
    void save(EventFileTrigger trigger);

    /** Deletes the engine-created triggers with these keys ({@code <workflow>/<trigger>}). */
    void deleteApiTriggers(Collection<String> keys);
}
