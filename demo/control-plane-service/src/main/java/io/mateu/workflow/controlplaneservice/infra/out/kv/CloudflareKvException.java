package io.mateu.workflow.controlplaneservice.infra.out.kv;

public class CloudflareKvException extends RuntimeException {
    public CloudflareKvException(String message) {
        super(message);
    }

    public CloudflareKvException(String message, Throwable cause) {
        super(message, cause);
    }
}