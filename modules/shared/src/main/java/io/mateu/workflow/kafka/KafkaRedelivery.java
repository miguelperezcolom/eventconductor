package io.mateu.workflow.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * The container error handler every EventConductor Kafka consumer runs with: a failed record is
 * redelivered with exponential back-off, <em>without limit</em>, and is never discarded. Only a
 * failure classified as not retryable reaches the recoverer, and the recoverer is the caller's — the
 * engine parks the record on its dead-letter topic.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Without it the binder leaves Spring Kafka's defaults in place, and those defaults throw work
 * away. A batch listener gets {@code DefaultErrorHandler(FixedBackOff(0, 9))}: the batch is retried
 * nine times with no pause and then <b>discarded</b> ({@code FallbackBatchErrorHandler: Records
 * discarded}), its offsets committed. A record listener gets the binder's in-memory retry
 * ({@code max-attempts}, default 3) and then the same: logged, committed, gone. The engine is built on
 * the opposite assumption — a retryable failure (an optimistic-lock conflict, a deadlock, the database
 * or broker being away) is rethrown precisely so that Kafka redelivers it. Ten immediate retries are
 * nothing against two consumers contending for one process, and a parallel {@code forEach} produced
 * that contention constantly: 12–19% of the benchmark's fan-out processes stopped for good, their
 * step events and worker replies discarded.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li><b>Record-level, not batch-level.</b> A batch listener that throws a
 *       {@link org.springframework.kafka.listener.BatchListenerFailedException} naming the failed record
 *       gets the records before it committed and the rest re-sought from it, so a conflict on one
 *       process does not redo the whole poll. A listener that cannot say which record failed gets the
 *       whole batch retried in place, with the same back-off and the same absence of a limit.</li>
 *   <li><b>Exponential back-off, unlimited.</b> Contention clears in milliseconds and an outage in
 *       minutes; the back-off starts short and caps at {@link Settings#maxInterval()} so a long outage
 *       is polled at a steady rate, not abandoned. {@link Settings#maxAttempts()} can bound it, for an
 *       operator who prefers a dead letter to a stalled partition; by default it is unbounded.</li>
 *   <li><b>Loud when it is not transient.</b> Every redelivery is reported to the {@link Observer}
 *       (the engine counts them), and a record still failing after {@link Settings#warnAfter()}
 *       attempts is logged at WARN — and again every {@code warnAfter} attempts — so a partition
 *       stalled behind a failure that will not clear is visible long before anyone notices the lag.
 *       The individual retries are not logged at ERROR: a conflict that resolves on the second attempt
 *       is the design working.</li>
 * </ul>
 *
 * <p>Offsets: a record is committed only once its listener returned, or once the recoverer parked it.
 * A recoverer that throws (a dead-letter topic the broker refuses) leaves the record to be retried
 * rather than committed.
 */
public final class KafkaRedelivery {

    private static final Logger log = LoggerFactory.getLogger(KafkaRedelivery.class);

    /**
     * Back-off and reporting settings.
     *
     * @param initialInterval the first pause
     * @param multiplier      growth of the pause from one attempt to the next
     * @param maxInterval     the longest pause; an outage is retried at this rate for as long as it lasts
     * @param maxAttempts     0 (the default) for no limit; otherwise the attempts after which a record is
     *                        handed to the recoverer
     * @param warnAfter       attempts after which a still-failing record is logged at WARN, and the
     *                        interval at which that is repeated
     */
    public record Settings(Duration initialInterval, double multiplier, Duration maxInterval, long maxAttempts,
                           int warnAfter) {

        public static final Settings DEFAULTS =
                new Settings(Duration.ofMillis(100), 2.0, Duration.ofSeconds(10), 0, 10);

        public Settings {
            if (initialInterval == null || initialInterval.isNegative()) {
                initialInterval = Duration.ofMillis(100);
            }
            if (maxInterval == null || maxInterval.compareTo(initialInterval) < 0) {
                maxInterval = initialInterval.compareTo(Duration.ofSeconds(10)) > 0
                        ? initialInterval : Duration.ofSeconds(10);
            }
            multiplier = multiplier < 1.0 ? 1.0 : multiplier;
            maxAttempts = Math.max(0, maxAttempts);
            warnAfter = Math.max(1, warnAfter);
        }

        public boolean unlimited() {
            return maxAttempts <= 0;
        }
    }

    /** What a consumer wants to know about its redeliveries: typically, metrics. */
    public interface Observer {

        /** A record (or, when the listener could not say which, a batch) failed and will be redelivered. */
        default void redelivered(String topic, Throwable cause, int attempt) {
        }

        /** A record was handed to the recoverer: not retryable, or out of attempts. */
        default void recovered(String topic, Throwable cause) {
        }

        Observer NONE = new Observer() {
        };
    }

    private KafkaRedelivery() {
    }

    /**
     * The error handler for one consumer.
     *
     * @param name         names the consumer in log lines
     * @param settings     back-off and reporting
     * @param recoverer    what happens to a record that is not retried (null: logged and skipped — for
     *                     consumers with nowhere to park, and only ever for non-retryable failures)
     * @param observer     told about every redelivery and every recovery
     * @param notRetryable failures that will fail the same way however often they are retried, on top of
     *                     Spring Kafka's own (deserialization, conversion, {@code ClassCastException}…)
     */
    @SafeVarargs
    public static DefaultErrorHandler errorHandler(String name, Settings settings, ConsumerRecordRecoverer recoverer,
                                                   Observer observer,
                                                   Class<? extends Exception>... notRetryable) {
        var effective = settings == null ? Settings.DEFAULTS : settings;
        var report = observer == null ? Observer.NONE : observer;
        var handler = recoverer == null
                ? new DefaultErrorHandler(backOff(effective))
                : new DefaultErrorHandler(recoverer, backOff(effective));
        if (notRetryable.length > 0) {
            handler.addNotRetryableExceptions(notRetryable);
        }
        // The retries themselves are expected — contention resolves on the next attempt. They are
        // counted and, past warnAfter, warned about by the listener below; the container's own ERROR
        // line per failed delivery would turn every optimistic-lock conflict into an alert.
        handler.setLogLevel(KafkaException.Level.DEBUG);
        handler.setRetryListeners(new Reporting(name, effective, report));
        return handler;
    }

    /** The back-off of {@link #errorHandler}: exponential, capped, and unlimited unless bounded. */
    public static ExponentialBackOff backOff(Settings settings) {
        var backOff = new ExponentialBackOff(settings.initialInterval().toMillis(), settings.multiplier());
        backOff.setMaxInterval(settings.maxInterval().toMillis());
        if (!settings.unlimited()) {
            // maxAttempts counts retries after the first delivery.
            backOff.setMaxAttempts(Math.max(0, settings.maxAttempts() - 1));
        }
        return backOff;
    }

    /**
     * The reporting half on its own — counted redeliveries and the WARN past {@code warnAfter} — for a
     * consumer that builds its own error handler.
     */
    public static RetryListener reporting(String name, Settings settings, Observer observer) {
        return new Reporting(name, settings == null ? Settings.DEFAULTS : settings,
                observer == null ? Observer.NONE : observer);
    }

    /** Counts and, past the threshold, warns. */
    static final class Reporting implements RetryListener {

        private final String name;
        private final Settings settings;
        private final Observer observer;

        Reporting(String name, Settings settings, Observer observer) {
            this.name = name;
            this.settings = settings;
            this.observer = observer;
        }

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
            report(record.topic() + "-" + record.partition() + "@" + record.offset(), record.topic(), ex,
                    deliveryAttempt);
        }

        @Override
        public void failedDelivery(ConsumerRecords<?, ?> records, Exception ex, int deliveryAttempt) {
            var first = records.iterator().hasNext() ? records.iterator().next() : null;
            var topic = first == null ? "?" : first.topic();
            report("a batch of " + records.count() + " record(s) from " + topic, topic, ex, deliveryAttempt);
        }

        private void report(String what, String topic, Exception ex, int attempt) {
            var cause = rootCause(ex);
            observer.redelivered(topic, cause, attempt);
            if (attempt >= settings.warnAfter() && attempt % settings.warnAfter() == 0) {
                log.warn("[{}] {} has failed {} times and is still being retried{} — a failure that does not "
                                + "clear stalls its partition. Last failure: {}: {}", name, what, attempt,
                        settings.unlimited() ? " (no limit)" : " (limit " + settings.maxAttempts() + ")",
                        cause.getClass().getName(), cause.getMessage());
            } else {
                log.debug("[{}] {} failed (attempt {}); redelivering: {}", name, what, attempt, cause.toString());
            }
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
            var cause = rootCause(ex);
            observer.recovered(record.topic(), cause);
            log.error("[{}] {}-{}@{} will not be retried ({}: {}); handed to the recoverer", name, record.topic(),
                    record.partition(), record.offset(), cause.getClass().getName(), cause.getMessage());
        }

        @Override
        public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
            log.error("[{}] could not recover {}-{}@{}; it stays uncommitted and will be retried", name,
                    record.topic(), record.partition(), record.offset(), failure);
        }
    }

    /** The innermost cause: the container and the binder each wrap the listener's exception once or twice. */
    public static Throwable rootCause(Throwable failure) {
        var cause = failure;
        var depth = 0;
        while (cause.getCause() != null && cause.getCause() != cause && depth++ < 20) {
            cause = cause.getCause();
        }
        return cause;
    }
}
