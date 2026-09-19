package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.Section;
import jakarta.validation.constraints.NotEmpty;

/**
 * A definition-level serialization lock: every process instance of the definition is serialized
 * against the others that resolve to the same {@link #key}, so only one runs at a time and the rest
 * are admitted FIFO. The process-level counterpart of a LOCK/UNLOCK critical section.
 *
 * <p>Carried in the {@code .ec} file as {@code processLock: { name, key }}. {@code name} is the lock
 * domain (defaults to the definition id when absent, so two definitions serialize together only if
 * they say the same name); {@code key} is a JEXL expression over the process variables — e.g.
 * {@code bookingId} to serialize by reservation.
 */
public record ProcessLock(
        String name,
        @Section("Process lock")
        @NotEmpty
        String key
) {
    /** The effective lock name: the declared one, or the definition id when none was given. */
    public String resolvedName(String definitionId) {
        return name == null || name.isBlank() ? definitionId : name;
    }
}
