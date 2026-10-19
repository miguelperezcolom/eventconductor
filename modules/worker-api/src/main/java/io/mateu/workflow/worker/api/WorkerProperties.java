package io.mateu.workflow.worker.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker runtime settings.
 *
 * <pre>
 *   eventconductor:
 *     worker:
 *       strict: false   # a task with no handler is warned about; true = fail it (ERROR)
 * </pre>
 */
@ConfigurationProperties(prefix = "eventconductor.worker")
public class WorkerProperties {

    /**
     * What to do with a dispatched task no registered handler serves. {@code false} (default) logs
     * a warning and ignores it; {@code true} replies {@code ERROR} so the gap is loud.
     */
    private boolean strict = false;

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    /** {@code eventconductor.worker.encryption.*}: how this worker treats sealed (sensitive) variables. */
    private final Encryption encryption = new Encryption();

    public Encryption getEncryption() {
        return encryption;
    }

    /**
     * With keys configured ({@code workflow.encryption.keys.<id>}), the worker opens sealed inputs and seals
     * the outputs named like them; {@code enabled=false} hands them to handlers sealed instead.
     * {@code sensitive-variables} adds output names (or globs) to seal even when no input of that name came
     * sealed.
     */
    public static class Encryption {

        private boolean enabled = true;
        private java.util.List<String> sensitiveVariables = new java.util.ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.util.List<String> getSensitiveVariables() {
            return sensitiveVariables;
        }

        public void setSensitiveVariables(java.util.List<String> sensitiveVariables) {
            this.sensitiveVariables = sensitiveVariables == null ? new java.util.ArrayList<>() : sensitiveVariables;
        }
    }

    /** The worker-side encryption these properties describe, over the application's keys. */
    public WorkerVariableEncryption workerEncryption(io.mateu.workflow.crypto.VariableEncryption keys) {
        if (!encryption.isEnabled() || keys == null || !keys.enabled()) {
            return WorkerVariableEncryption.NONE;
        }
        return new WorkerVariableEncryption(keys, encryption.getSensitiveVariables());
    }
}
