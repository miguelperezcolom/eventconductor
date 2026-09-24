package io.mateu.workflow.worker.http;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${secret:NAME}} references — the only form a credential may take in a definition —
 * at call time, in the worker. The value never enters a task variable, the outbox, a log or the process.
 * Lookup order: {@code workflow.http.secrets.NAME}, then the environment / Spring property {@code NAME}.
 */
public final class SecretResolver {

    private static final Pattern REFERENCE = Pattern.compile("^\\$\\{secret:([A-Za-z0-9_.-]+)}$");

    private final Function<String, String> lookup;

    public SecretResolver(Function<String, String> lookup) {
        this.lookup = lookup;
    }

    /** The value itself when it is not a reference (configuration may hold resolved values); else the secret. */
    public String resolve(String value) {
        if (value == null) {
            return null;
        }
        var matcher = REFERENCE.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        var name = matcher.group(1);
        var secret = lookup.apply(name);
        if (secret == null) {
            throw new MissingSecretException("Secret '" + name + "' is not configured (workflow.http.secrets."
                    + name + " or the " + name + " property/environment variable).");
        }
        return secret;
    }

    /** A referenced secret nobody configured: a deployment error retrying will not fix. */
    public static final class MissingSecretException extends RuntimeException {
        public MissingSecretException(String message) {
            super(message);
        }
    }
}
