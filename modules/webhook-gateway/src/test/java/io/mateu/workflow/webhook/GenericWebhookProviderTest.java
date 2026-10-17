package io.mateu.workflow.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericWebhookProviderTest {

    final GenericWebhookProvider provider = new GenericWebhookProvider();
    final byte[] body = "{\"orderId\": \"O-1\"}".getBytes(StandardCharsets.UTF_8);

    static WebhookGatewayProperties.Endpoint endpoint() {
        var endpoint = new WebhookGatewayProperties.Endpoint();
        endpoint.setDestination("partner-events");
        return endpoint;
    }

    @Test
    void readsTheAttributesFromTheCeHeadersByDefault() {
        var headers = Map.of("ce-type", "com.partner.order.changed", "ce-id", "evt-9", "ce-subject", "O-1",
                "content-type", "application/json; charset=utf-8");

        var event = provider.read("partner", endpoint(), headers, body);

        assertThat(event.type()).isEqualTo("com.partner.order.changed");
        assertThat(event.id()).isEqualTo("evt-9");
        assertThat(event.subject()).isEqualTo("O-1");
        assertThat(event.key()).isEqualTo("O-1");
        assertThat(event.source()).isEqualTo("partner");
        assertThat(event.dataContentType()).isEqualTo("application/json; charset=utf-8");
        assertThat(event.data()).isEqualTo("{\"orderId\": \"O-1\"}");
    }

    @Test
    void fallsBackToTheConfiguredTypeAndAFreshIdAndNeedsAType() {
        var endpoint = endpoint();
        endpoint.setType("com.partner.ping");
        endpoint.setSource("partner.example.com");

        var event = provider.read("partner", endpoint, Map.of(), body);
        assertThat(event.type()).isEqualTo("com.partner.ping");
        assertThat(event.source()).isEqualTo("partner.example.com");
        assertThat(event.id()).isNotBlank();
        assertThat(event.subject()).isNull();

        assertThatThrownBy(() -> provider.read("partner", endpoint(), Map.of(), body))
                .isInstanceOf(WebhookRejectedException.class)
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(400));
    }

    @Test
    void verifiesTheSignatureWhenASecretIsConfiguredInTheHeaderTheEndpointNames() {
        var endpoint = endpoint();
        endpoint.setSecret("s3cret");
        endpoint.setSignatureHeader("X-Partner-Signature");
        endpoint.setType("t");
        var headers = new HashMap<String, String>();
        headers.put("x-partner-signature", Signatures.hmacSha256Hex("s3cret", body).toUpperCase());

        assertThat(provider.read("partner", endpoint, headers, body).type()).isEqualTo("t");

        headers.put("x-partner-signature", "sha256=" + Signatures.hmacSha256Hex("other", body));
        assertThatThrownBy(() -> provider.read("partner", endpoint, headers, body))
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(401));
        assertThatThrownBy(() -> provider.read("partner", endpoint, Map.of(), body))
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(401));
    }

    @Test
    void aSignedEndpointWithAFixedTypeIgnoresTheTypeHeader() {
        // Only the body is signed: a captured request replayed with another CE-Type must not become another event.
        var endpoint = endpoint();
        endpoint.setSecret("s3cret");
        endpoint.setType("com.partner.order.changed");
        var headers = new HashMap<String, String>();
        headers.put("x-signature-256", Signatures.hmacSha256Hex("s3cret", body));
        headers.put("ce-type", "com.partner.order.cancelled");

        assertThat(provider.read("partner", endpoint, headers, body).type()).isEqualTo("com.partner.order.changed");

        // Unsigned, or signed without a fixed type, the header still says the type.
        var unsigned = endpoint();
        unsigned.setType("com.partner.order.changed");
        assertThat(provider.read("partner", unsigned, headers, body).type()).isEqualTo("com.partner.order.cancelled");
        var noFixedType = endpoint();
        noFixedType.setSecret("s3cret");
        assertThat(provider.read("partner", noFixedType, headers, body).type()).isEqualTo("com.partner.order.cancelled");
    }
}
