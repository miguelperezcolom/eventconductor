package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import io.mateu.workflow.template.Templates;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Turns a PUBLISH_EVENT step and the process's state into the event to publish: the destination
 * checked against configuration, the payload and key rendered from their templates.
 *
 * @throws IllegalArgumentException for a misconfigured step (unknown destination, too large, …)
 * @throws Templates.TemplateException for a template that cannot be rendered
 */
public final class ExternalEventRenderer {

    private static final Set<String> FORMATS = Set.of("binary", "structured", "plain");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public static ExternalEventRequested render(Step step, Process process, String stepExecutionId,
                                                EventDestinationsProperties destinations, boolean kafkaMode) {
        var event = step.event();
        if (event == null || blank(event.destination()) || blank(event.type())) {
            throw new IllegalArgumentException("PUBLISH_EVENT step '" + step.id() + "' needs an event with a destination and a type.");
        }
        var configured = destinations == null ? java.util.Map.<String, EventDestinationsProperties.Destination>of()
                : destinations.getDestinations();
        // In kafka mode a destination must name a topic. In embedded mode the application's publisher
        // receives the logical name — but once any destination is configured, the list is the list.
        if ((kafkaMode || !configured.isEmpty()) && !configured.containsKey(event.destination())) {
            throw new IllegalArgumentException("PUBLISH_EVENT destination '" + event.destination()
                    + "' is not configured (workflow.events.destinations." + event.destination() + ").");
        }
        if (event.format() != null && !FORMATS.contains(event.format())) {
            throw new IllegalArgumentException("PUBLISH_EVENT format '" + event.format() + "' is not one of " + FORMATS + ".");
        }
        if (event.payloadSources() > 1) {
            throw new IllegalArgumentException("PUBLISH_EVENT step '" + step.id()
                    + "' declares more than one of payload, payloadTemplate and payloadVariables.");
        }
        var context = TemplateContext.of(process, step, stepExecutionId);
        String data;
        if (event.payload() != null) {
            data = Templates.renderJson(event.payload(), context);
        } else if (!blank(event.payloadTemplate())) {
            data = Templates.renderText(event.payloadTemplate(), context);
        } else if (event.payloadVariables() != null && !event.payloadVariables().isEmpty()) {
            var object = new LinkedHashMap<String, Object>();
            event.payloadVariables().forEach(name -> object.put(name, process.getVariables().stream()
                    .filter(variable -> name.equals(variable.name())).map(v -> (Object) v.value())
                    .findFirst().orElse(null)));
            try {
                data = JSON.writeValueAsString(object);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("The event payload is not representable as JSON", e);
            }
        } else {
            data = "{}";
        }
        long max = destinations == null ? 262_144 : destinations.getMaxPayloadBytes();
        var size = data.getBytes(StandardCharsets.UTF_8).length;
        if (max > 0 && size > max) {
            throw new IllegalArgumentException("The event payload is " + size + " bytes, over the " + max
                    + "-byte limit (workflow.events.max-payload-bytes).");
        }
        String key = blank(event.key()) ? null : Templates.renderText(event.key(), context);
        if (blank(key)) {
            key = !blank(process.getBusinessKey()) ? process.getBusinessKey() : process.getId();
        }
        return new ExternalEventRequested(stepExecutionId, event.destination(), event.type(), key, event.format(),
                data, process.getId(), process.getWorkflowDefinitionId(), step.id(), process.getBusinessKey(),
                Instant.now().toString());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private ExternalEventRenderer() {
    }
}
