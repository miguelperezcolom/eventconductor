package io.mateu.workflow.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Any sender that can be told where to post: the attributes come from headers the endpoint names
 * (type, id, subject — CloudEvents' own {@code CE-*} binary headers by default) or from the
 * endpoint's configuration, and the body is the payload as it came. With a {@code secret}, the
 * {@code signatureHeader} must carry the hex HMAC-SHA256 of the body ({@code sha256=} prefix tolerated);
 * without one, the endpoint accepts what it is sent — keep it behind something that checks.
 *
 * <p>The signature covers the body only, never the headers. So a signed endpoint with a configured
 * {@code type} always uses that type and ignores the type header (a replayed body cannot be relabelled);
 * the id still comes from an unsigned header, so de-duplication of a replay relies on the sender's id.
 */
public class GenericWebhookProvider implements WebhookProvider {

    @Override
    public WebhookEvent read(String endpointName, WebhookGatewayProperties.Endpoint endpoint, Map<String, String> headers,
                             byte[] body) {
        var signed = endpoint.getSecret() != null && !endpoint.getSecret().isBlank();
        if (signed) {
            var header = endpoint.getSignatureHeader() == null ? "x-signature-256"
                    : endpoint.getSignatureHeader().toLowerCase(Locale.ROOT);
            if (!Signatures.matches(endpoint.getSecret(), body, headers.get(header))) {
                throw new WebhookRejectedException(401, "Missing or invalid " + endpoint.getSignatureHeader() + ".");
            }
        }
        // Only the body is signed: on a signed endpoint with a fixed type, a header must not change what
        // the event is — a captured request replayed with another CE-Type would otherwise be accepted.
        var fixedType = endpoint.getType() != null && !endpoint.getType().isBlank();
        var type = signed && fixedType ? null : header(headers, endpoint.getTypeHeader());
        if (type == null) {
            type = endpoint.getType();
        }
        if (type == null || type.isBlank()) {
            throw new WebhookRejectedException(400, "No event type: neither the " + endpoint.getTypeHeader()
                    + " header nor workflow.webhooks.endpoints." + endpointName + ".type says one.");
        }
        var id = header(headers, endpoint.getIdHeader());
        var subject = header(headers, endpoint.getSubjectHeader());
        var source = endpoint.getSource() == null || endpoint.getSource().isBlank() ? endpointName : endpoint.getSource();
        var contentType = headers.getOrDefault("content-type", "application/json");
        var text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        return new WebhookEvent(id == null ? UUID.randomUUID().toString() : id, source, type.trim(), subject,
                Instant.now().toString(), subject, contentType, text, Map.of());
    }

    private static String header(Map<String, String> headers, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var value = headers.get(name.toLowerCase(Locale.ROOT));
        return value == null || value.isBlank() ? null : value.trim();
    }
}
