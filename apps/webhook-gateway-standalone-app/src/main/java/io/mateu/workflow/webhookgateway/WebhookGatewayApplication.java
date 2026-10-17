package io.mateu.workflow.webhookgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The webhook gateway: one HTTP endpoint per configured sender (GitHub, or any HMAC-signed one),
 * each delivery verified, turned into a CloudEvent and published on its destination — where the
 * engine's event triggers and {@code onEvent} waits pick it up as they would any domain event.
 */
@SpringBootApplication
public class WebhookGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookGatewayApplication.class, args);
    }
}
