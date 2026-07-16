package io.mateu.workflow.infra.out.async;

/**
 * Helpers shared by the outbox relays.
 */
final class OutboxMessages {

    private static final String ALLOWED_PACKAGE_PREFIX = "io.mateu.";

    private OutboxMessages() {
    }

    /**
     * Resolves the class an outbox message payload deserializes to. The message type is
     * stored as a string in the database, so it must be validated before loading: only
     * event classes of this platform are acceptable — anything else would let a tampered
     * row load arbitrary classes.
     */
    static Class<?> messageClass(String messageType) throws ClassNotFoundException {
        if (messageType == null || !messageType.startsWith(ALLOWED_PACKAGE_PREFIX)) {
            throw new ClassNotFoundException(
                    "Outbox message type '" + messageType + "' is not an allowed event class");
        }
        return Class.forName(messageType);
    }
}
