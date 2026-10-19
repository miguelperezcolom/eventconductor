package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry of a USER_TASK's {@code escalation}: what happens when the task is still open
 * {@code after} so long from its creation.
 *
 * <ul>
 *   <li>{@code reassign} — the task is for {@code to} from now on (its assignee and candidates
 *       replaced);</li>
 *   <li>{@code notify} — nothing changes on the task; the escalation is recorded and announced;</li>
 *   <li>{@code escalateTo} — the task is closed and the process goes on at the step named in
 *       {@code escalateTo}, the way {@code onTimeoutStepId} routes a timed-out step.</li>
 * </ul>
 *
 * Any of them may also publish a domain event ({@code publishEvent}, the shape of a PUBLISH_EVENT's
 * {@code event}) — the hook for a notification. Rules: {@code io.mateu.workflow.analysis.UserTaskRules}.
 *
 * @param after        ISO-8601 duration from the task's creation, e.g. {@code PT48H}
 * @param action       {@code reassign}, {@code notify} or {@code escalateTo}; inferred when absent
 * @param to           reassign only: who the task is for from now on
 * @param escalateTo   escalateTo only: the step the process goes on at
 * @param publishEvent an event to publish when it fires
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Escalation(
        String after,
        String action,
        TaskAssignment to,
        String escalateTo,
        PublishEvent publishEvent
) {

    /** {@link #after} as a duration; zero when it cannot be read (the rules refuse such a definition). */
    public Duration afterDuration() {
        var duration = io.mateu.workflow.analysis.UserTaskRules.parseDuration(after);
        return duration == null ? Duration.ZERO : duration;
    }

    /** The action this entry takes, explicit or inferred. */
    public String effectiveAction() {
        var action = io.mateu.workflow.analysis.UserTaskRules.actionOf(asMap());
        return action == null ? io.mateu.workflow.analysis.UserTaskRules.NOTIFY : action;
    }

    /** As the map the analysis rules read. */
    public Map<String, Object> asMap() {
        var map = new LinkedHashMap<String, Object>();
        if (after != null) map.put("after", after);
        if (action != null) map.put("action", action);
        if (to != null) map.put("to", to.asMap());
        if (escalateTo != null) map.put("escalateTo", escalateTo);
        if (publishEvent != null) {
            var event = new LinkedHashMap<String, Object>();
            if (publishEvent.destination() != null) event.put("destination", publishEvent.destination());
            if (publishEvent.type() != null) event.put("type", publishEvent.type());
            map.put("publishEvent", event);
        }
        return map;
    }
}
