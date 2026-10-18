package io.mateu.workflow.application.httpworkers;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.domain.services.HttpRequestRenderer;
import io.mateu.workflow.domain.services.LlmRequestRenderer;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which tasks are served over HTTP: those whose step topic is listed in
 * {@code workflow.workers.http.topics}, or — with {@code *} — every topic except the built-in tasks
 * (USER_TASK, RULE, HTTP_CALL, LLM_CALL), whose own runtimes keep serving them unless their topic is
 * named explicitly.
 */
public final class HttpTaskRouting {

    public static final String ALL = "*";

    /** The task ids the engine itself gives the built-in step types. */
    static final Set<String> BUILT_IN_TASK_IDS = Set.of("complete-form", "evaluate-rule",
            HttpRequestRenderer.TASK_ID, LlmRequestRenderer.TASK_ID);

    /** The default topics of the built-in tasks that have one of their own. */
    static final Set<String> BUILT_IN_TOPICS = Set.of(HttpRequestRenderer.DEFAULT_TOPIC,
            LlmRequestRenderer.DEFAULT_TOPIC);

    private final Set<String> explicit;
    private final boolean all;

    public HttpTaskRouting(Collection<String> topics) {
        var cleaned = (topics == null ? List.<String>of() : topics).stream()
                .filter(topic -> topic != null && !topic.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        this.all = cleaned.remove(ALL);
        this.explicit = Set.copyOf(cleaned);
    }

    /** Whether a task on {@code topic} (null/blank: the default topic) with {@code taskId} is served over HTTP. */
    public boolean routesTask(String topic, String taskId) {
        var destination = DownstreamEventPublisher.destinationFor(topic);
        if (explicit.contains(destination)) {
            return true;
        }
        return all && !BUILT_IN_TOPICS.contains(destination)
                && (taskId == null || !BUILT_IN_TASK_IDS.contains(taskId));
    }

    /** Whether the topic is named explicitly: its cancellations have nowhere else to go. */
    public boolean namesTopic(String topic) {
        return explicit.contains(DownstreamEventPublisher.destinationFor(topic));
    }

    /** Whether any topic is routed at all. */
    public boolean routesAnything() {
        return all || !explicit.isEmpty();
    }

    @Override
    public String toString() {
        return all ? (explicit.isEmpty() ? "*" : "* + " + explicit) : explicit.toString();
    }
}
