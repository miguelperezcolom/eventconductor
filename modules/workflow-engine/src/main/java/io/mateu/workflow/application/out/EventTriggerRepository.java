package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.EventFileTrigger;

import java.util.List;

/**
 * The event triggers declared outside the definitions — the entries of every imported
 * {@code events.yaml}, by the source they came from. A definition's own {@code triggers} ride on its
 * row; this holds the rest, so every pod matches inbound events against the same set.
 */
public interface EventTriggerRepository {

    /** Every stored entry, by source then index. */
    List<EventFileTrigger> findAll();

    /** Replaces the entries of {@code source} with {@code triggers} (empty or null clears the source). */
    void replace(String source, List<EventFileTrigger> triggers);
}
