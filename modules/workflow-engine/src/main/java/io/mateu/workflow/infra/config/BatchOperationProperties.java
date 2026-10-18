package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Batch operations: how the background runner walks them.
 *
 * <pre>
 *   workflow:
 *     batch-operations:
 *       enabled: true          # the runner; off leaves batches CREATED until it is turned on
 *       poll-interval: 1s      # pause between two pages
 *       page-size: 100         # processes read, and acted on, per page
 *       max-per-second: 50     # the throttle: processes acted on per second (0: unthrottled)
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.batch-operations")
@Getter
@Setter
public class BatchOperationProperties {

    /** Whether the runner runs on this node. Batches can still be created and cancelled when it does not. */
    private boolean enabled = true;

    /** The pause between two pages of a batch — and how soon a new batch is picked up. */
    private Duration pollInterval = Duration.ofSeconds(1);

    /** How many processes one page reads and acts on before the progress is saved. */
    private int pageSize = 100;

    /**
     * At most this many processes a second are acted on, so a batch over a hundred thousand processes
     * does not crowd out the engine's own work. Zero or less: as fast as the commands go.
     */
    private int maxPerSecond = 50;

    /** How many batches the console and the list endpoints show, newest first. */
    private int listLimit = 200;
}
