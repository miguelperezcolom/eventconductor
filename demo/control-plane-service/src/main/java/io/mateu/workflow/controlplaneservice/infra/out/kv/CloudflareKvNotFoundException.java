package io.mateu.workflow.controlplaneservice.infra.out.kv;

public class CloudflareKvNotFoundException extends CloudflareKvException {
    public CloudflareKvNotFoundException(String message) {
        super(message);
    }
}