package io.mateu.workflow.webhook;

/** A webhook the gateway does not publish, with the HTTP status its sender gets. */
public class WebhookRejectedException extends RuntimeException {

    private final int status;

    public WebhookRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
