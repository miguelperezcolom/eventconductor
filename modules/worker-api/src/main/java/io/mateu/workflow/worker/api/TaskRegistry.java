package io.mateu.workflow.worker.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The task registrations this service serves, keyed by {@code <id>@<version>}. Resolution is by the
 * dispatched {@code taskId} first and the {@code stepId} second (the fallback for an ACTION with no
 * contract, whose taskId is empty), matching the engine's own precedence.
 */
public final class TaskRegistry {

    private final Map<String, TaskRegistration<?, ?>> byKey = new LinkedHashMap<>();

    public TaskRegistry(List<? extends TaskRegistration<?, ?>> registrations) {
        for (var registration : registrations) {
            var previous = byKey.put(registration.ref(), registration);
            if (previous != null) {
                throw new IllegalStateException("Two task handlers are registered for '"
                        + registration.ref() + "'.");
            }
        }
    }

    /** The handler for a dispatched task, or null when none serves it. */
    public TaskRegistration<?, ?> resolve(String taskId, String stepId) {
        if (taskId != null && !taskId.isBlank()) {
            var byTaskId = byKey.get(taskId);
            if (byTaskId != null) {
                return byTaskId;
            }
        }
        return stepId == null ? null : byKey.get(stepId);
    }

    public List<String> refs() {
        return List.copyOf(byKey.keySet());
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }
}
