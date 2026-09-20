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
}
