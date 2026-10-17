package io.mateu.workflow.webhook;

import java.util.Map;

/**
 * A webhook as a CloudEvent, the shape the engine receives: the attributes, the record key and the
 * payload — exactly what {@code PUBLISH_EVENT} writes and the engine's inbound consumer reads.
 *
 * @param id              the CloudEvents id: the sender's delivery id when it has one, so a redelivery
 *                        is the same event and the engine uses it once
 * @param source          the CloudEvents source
 * @param type            the CloudEvents type, what triggers match on
 * @param subject         the CloudEvents subject, optional
 * @param time            the CloudEvents time, RFC 3339
 * @param key             the record key (the default correlation key of an {@code onEvent} wait), optional
 * @param dataContentType the payload's media type
 * @param data            the payload as text
 * @param extensions      other attributes, lower-case names
 */
public record WebhookEvent(String id, String source, String type, String subject, String time, String key,
                           String dataContentType, String data, Map<String, String> extensions) {
}
