package io.mateu.workflow.infra.in.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.InboundEvent;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads a CloudEvent 1.0 as it arrives: from a Kafka record — <b>binary</b> content mode
 * ({@code ce_*} headers, the payload as the value: what {@code PUBLISH_EVENT} writes by default),
 * <b>structured</b> ({@code application/cloudevents+json}, the whole event as the value) or a
 * <b>plain</b> record (no envelope: id {@code <topic>-<partition>-<offset>}, source
 * {@code kafka://<topic>}, no type) — or from the structured JSON body of the REST and MCP inject.
 */
public final class InboundCloudEvents {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The context attributes CloudEvents defines; anything else is an extension. */
    private static final Set<String> ATTRIBUTES = Set.of("specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data", "data_base64");

    private InboundCloudEvents() {
    }

    /**
     * A Kafka record as an event on {@code destination}.
     *
     * @param headers the record's headers by name (last value wins), as text
     */
    public static InboundEvent fromRecord(String destination, String topic, int partition, long offset,
                                          byte[] key, byte[] value, Map<String, String> headers) {
        var keyText = key == null ? null : new String(key, StandardCharsets.UTF_8);
        var valueText = value == null ? null : new String(value, StandardCharsets.UTF_8);
        if (headers.containsKey("ce_id") || headers.containsKey("ce_specversion")) {
            var extensions = new LinkedHashMap<String, String>();
            headers.forEach((name, header) -> {
                if (name.startsWith("ce_") && !ATTRIBUTES.contains(name.substring(3))) {
                    extensions.put(name.substring(3), header);
                }
            });
            // Binary mode: ce_datacontenttype, else the record's own content type — the CloudEvents Kafka
            // binding maps datacontenttype to the standard content-type header.
            var contentType = headers.containsKey("ce_datacontenttype") ? headers.get("ce_datacontenttype")
                    : contentType(headers);
            return new InboundEvent(destination, headers.get("ce_id"), headers.get("ce_source"), headers.get("ce_type"),
                    headers.get("ce_subject"), headers.get("ce_time"), keyText, contentType, valueText, extensions);
        }
        var contentType = contentType(headers);
        if (valueText != null && (contentType != null && contentType.contains("cloudevents")
                || looksStructured(valueText))) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = JSON.readValue(valueText, Map.class);
                return fromStructured(destination, envelope, keyText);
            } catch (Exception notAnEnvelope) {
                // fall through: a plain record that merely starts like one
            }
        }
        return new InboundEvent(destination, topic + "-" + partition + "-" + offset, "kafka://" + topic, null, null,
                null, keyText, contentType, valueText, Map.of());
    }

    /**
     * A structured CloudEvent — {@code {specversion, id, source, type, subject?, time?, datacontenttype?,
     * data, ...extensions}} — as an event on {@code destination}. {@code data} may be any JSON value; it
     * is kept as JSON text (a string as its text). The key is the Kafka record key, or the {@code key}
     * the inject was given (also read from the envelope's own {@code key} member when present).
     */
    public static InboundEvent fromStructured(String destination, Map<String, Object> envelope, String key) {
        var extensions = new LinkedHashMap<String, String>();
        envelope.forEach((name, value) -> {
            if (!ATTRIBUTES.contains(name) && !"key".equals(name) && value != null) {
                extensions.put(name, value instanceof String s ? s : write(value));
            }
        });
        var data = envelope.get("data");
        String dataText = data == null ? null : data instanceof String s ? s : write(data);
        var effectiveKey = key != null ? key : text(envelope.get("key"));
        return new InboundEvent(destination, text(envelope.get("id")), text(envelope.get("source")),
                text(envelope.get("type")), text(envelope.get("subject")), text(envelope.get("time")), effectiveKey,
                text(envelope.get("datacontenttype")), dataText, extensions);
    }

    /**
     * The record's content type: Spring's {@code contentType} header, else the standard
     * {@code content-type} (what the CloudEvents Kafka binding and most producers write) — header names
     * matched in any case.
     */
    static String contentType(Map<String, String> headers) {
        String standard = null;
        for (var header : headers.entrySet()) {
            if (header.getKey() == null || header.getValue() == null) {
                continue;
            }
            if (header.getKey().equalsIgnoreCase("contentType")) {
                return header.getValue();
            }
            if (header.getKey().equalsIgnoreCase("content-type")) {
                standard = header.getValue();
            }
        }
        return standard;
    }

    private static boolean looksStructured(String value) {
        var trimmed = value.strip();
        return trimmed.startsWith("{") && trimmed.contains("\"specversion\"") && trimmed.contains("\"id\"");
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
