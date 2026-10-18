package io.mateu.workflow.worker.http;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${secret:NAME}} references — the only form a credential may take in a definition —
 * at call time, in the worker. The value never enters a task variable, the outbox, a log or the process.
 * Lookup order: {@code workflow.http.secrets.NAME}, then — only when
 * {@code workflow.http.secrets-from-environment=true} — the environment / Spring property {@code NAME}.
 */
public final class SecretResolver {

    private static final Pattern REFERENCE = Pattern.compile("^\\$\\{secret:([A-Za-z0-9_.-]+)}$");

    private final Function<String, String> lookup;
    private final boolean fromEnvironment;

    /** A lookup over the listed secrets only ({@code workflow.http.secrets}). */
    public SecretResolver(Function<String, String> lookup) {
        this(lookup, false);
    }

    /**
     * @param lookup          name to value, or null when not configured
     * @param fromEnvironment whether {@code lookup} also falls back to the environment — only then does
     *                        the missing-secret message suggest an environment variable
     */
    public SecretResolver(Function<String, String> lookup, boolean fromEnvironment) {
        this.lookup = lookup;
        this.fromEnvironment = fromEnvironment;
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
            throw new MissingSecretException(fromEnvironment
                    ? "Secret '" + name + "' is not configured (workflow.http.secrets." + name + " or the " + name
                            + " property/environment variable)."
                    : "Secret '" + name + "' is not configured: list it under workflow.http.secrets." + name
                            + " (the environment is not consulted unless workflow.http.secrets-from-environment=true).");
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
