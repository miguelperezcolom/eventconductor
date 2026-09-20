package io.mateu.workflow.worker.api;

/**
 * Binds a task contract (by id and version) to the handler that serves it and the input/output
 * types its variables map to. The generated code produces one of these per implemented task and
 * exposes it as a Spring bean; the runtime collects them into a registry keyed by
 * {@code <id>@<version>}.
 *
 * @param <I> the input record type
 * @param <O> the output record type
 */
public record TaskRegistration<I, O>(
        String id,
        int version,
        String topic,
        Class<I> inputType,
        Class<O> outputType,
        TaskHandler<I, O> handler) {

    /** {@code <id>@<version>} — the taskId the engine dispatches and the registry key. */
    public String ref() {
        return id + "@" + version;
    }
}
