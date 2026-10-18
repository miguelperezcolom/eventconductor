package io.mateu.workflow.webhook;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a webhook body without letting the sender decide how much memory it takes: a webhook is read
 * before its signature can be checked, so anybody who reaches the endpoint could otherwise send an
 * endless body. {@code Content-Length} answers most of it; a chunked body is read one byte past the
 * limit and refused.
 */
public final class WebhookBodies {

    /** A body over the limit; callers answer 413. */
    public static final class TooLargeException extends RuntimeException {
        public TooLargeException(long maxBytes) {
            super("The webhook body is over the " + maxBytes + " byte limit (workflow.rest.max-body-bytes).");
        }
    }

    private WebhookBodies() {
    }

    /**
     * @param in             the request body, or null for none
     * @param declaredLength the {@code Content-Length}, or -1 when unknown
     * @param maxBytes       the most that is read
     */
    public static byte[] read(InputStream in, long declaredLength, long maxBytes) throws IOException {
        if (declaredLength > maxBytes) {
            throw new TooLargeException(maxBytes);
        }
        if (in == null) {
            return new byte[0];
        }
        var body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        if (body.length > maxBytes) {
            throw new TooLargeException(maxBytes);
        }
        return body;
    }
}
