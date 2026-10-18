package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process retention: how long finished processes are kept, engine-wide, and how the purge job runs.
 * A definition's own {@code retention} wins over both defaults here.
 *
 * <pre>
 *   workflow:
 *     processes:
 *       retention: 30d              # every terminal status (unset: keep forever — the default)
 *       retention-by-status:
 *         error: 180d               # a terminal status: completed, cancelled, error, compensated,
 *         compensation-failed: 180d #   compensation-failed (also written compensationFailed)
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.processes")
@Getter
@Setter
public class ProcessRetentionProperties {

    /**
     * How long a finished process is kept when its definition does not say, for every terminal
     * status. Unset (the default) keeps them forever — today's behaviour: nothing is deleted that a
     * definition did not ask to delete.
     */
    private Duration retention;

    /** Per terminal status, over {@link #retention}; keys are the status names in any case or separator style. */
    private Map<String, Duration> retentionByStatus = new LinkedHashMap<>();

    /** The purge job. */
    private Purge purge = new Purge();

    @Getter
    @Setter
    public static class Purge {
        /** Whether the purge job runs at all — off stops every deletion, whatever the retentions say. */
        private boolean enabled = true;
        /** How often it looks for processes past their retention. */
        private Duration interval = Duration.ofMinutes(10);
        /** How many process trees it reads per query. */
        private int batchSize = 100;
        /**
         * How many processes one pass may delete: a backlog — retention just switched on over years of
         * history — is worked off over several passes rather than in one sweep that competes with the
         * engine for the database.
         */
        private int maxPerPass = 10_000;
    }
}
