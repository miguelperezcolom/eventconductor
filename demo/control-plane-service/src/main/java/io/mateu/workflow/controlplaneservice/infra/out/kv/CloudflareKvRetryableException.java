package io.mateu.workflow.controlplaneservice.infra.out.kv;

public class CloudflareKvRetryableException extends CloudflareKvException {
    public CloudflareKvRetryableException(String message) {
        super(message);
    }

    public CloudflareKvRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}