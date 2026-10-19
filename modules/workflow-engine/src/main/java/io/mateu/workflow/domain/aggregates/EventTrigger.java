package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One event trigger of a definition: when a domain event of {@code type} arrives on
 * {@code destination} and {@code filter} holds, the engine starts a process of the definition. Carried
 * in the {@code .ec} file as an entry of {@code triggers}, beside {@code schedules}.
 *
 * <p>Every template and the filter are evaluated over the <em>event context</em> ({@code id},
 * {@code source}, {@code type}, {@code subject}, {@code time}, {@code key}, {@code destination},
 * {@code datacontenttype}, {@code data}, {@code extensions}; see {@code InboundEventContext}).
 *
 * @param id          the trigger's name, unique in the definition; null means derived from the type
 *                    ({@code EventTriggerRules.effectiveIds}). A pause is kept under it, and the
 *                    default business key carries it.
 * @param destination a logical destination, mapped to a topic by
 *                    {@code workflow.events.destinations.<name>.topic} — the same names
 *                    {@code PUBLISH_EVENT} publishes to, never a topic itself
 * @param type        the CloudEvents type to match; {@code *} is a wildcard ({@code com.acme.booking.*})
 * @param filter      optional JEXL over the event that must be true ({@code data.channel == 'web'});
 *                    an expression that cannot be evaluated is false
 * @param businessKey optional text template for the started process's business key; null means
 *                    {@code <definition>-evt-<trigger>-<hash of the event's source and id>}, so a
 *                    redelivered event collapses into the process it already started
 * @param variables   the started process's initial variables, each a template over the event; null
 *                    means the top-level members of {@code data} (when it is a JSON object); empty means none
 * @param startPolicy optional per-key flow control of the starts this trigger makes (debounce, throttle,
 *                    singleton; its key a template over the event); null means the definition's, if any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "destination", "type", "filter", "businessKey", "variables", "startPolicy"})
public record EventTrigger(String id, String destination, String type, String filter, String businessKey,
                           Map<String, Object> variables, StartPolicy startPolicy) {

    /** The shape before flow control: a trigger with no start policy of its own. */
    public EventTrigger(String id, String destination, String type, String filter, String businessKey,
                        Map<String, Object> variables) {
        this(id, destination, type, filter, businessKey, variables, null);
    }

    public EventTrigger {
        id = blank(id) ? null : id.trim();
        destination = destination == null ? null : destination.trim();
        type = type == null ? null : type.trim();
        filter = blank(filter) ? null : filter;
        businessKey = blank(businessKey) ? null : businessKey;
        // Insertion order kept (the author's order); null stays null so the exporter writes nothing.
        variables = variables == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /** The same trigger under another id. */
    public EventTrigger withId(String newId) {
        return new EventTrigger(newId, destination, type, filter, businessKey, variables, startPolicy);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
