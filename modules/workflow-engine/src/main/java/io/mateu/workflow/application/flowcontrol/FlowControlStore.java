package io.mateu.workflow.application.flowcontrol;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where flow control keeps its state: per key, the throttle's window and the last start (for a singleton);
 * and the starts waiting for their moment — the debounce (one per key) and the throttle queue (FIFO).
 *
 * <p>Everything about one key is read and changed inside {@link #withKey}, which holds the key exclusively
 * for its duration — across pods, in the database implementation ({@code SELECT … FOR UPDATE} on the key's
 * row). Keys never wait for each other.
 */
public interface FlowControlStore {

    /** Runs {@code action} holding {@code keyId} exclusively; creates the key's state on first use. */
    <T> T withKey(String keyId, String scope, String flowKey, Function<KeyTx, T> action);

    /** Keys with a pending start due at or before {@code now}, at most {@code max}. */
    List<String> dueKeys(LocalDateTime now, int max);

    /** The earliest moment a pending start falls due, if any is pending. */
    Optional<LocalDateTime> nextDueAt();

    /** How many starts wait, per kind ({@code DEBOUNCE}, {@code THROTTLE}). */
    long pending(Kind kind);

    /** What waits: a debounced start, or a start queued by a throttle. */
    enum Kind { DEBOUNCE, THROTTLE }

    /**
     * One key's state.
     *
     * @param windowStart     when the throttle's current window opened; null before the first start
     * @param windowCount     starts admitted in that window
     * @param lastProcessId   the process the policy started last, when known
     * @param lastBusinessKey its business key
     * @param lastStartedAt   when it was started
     */
    record KeyState(String keyId, String scope, String flowKey, LocalDateTime windowStart, int windowCount,
                    String lastProcessId, String lastBusinessKey, LocalDateTime lastStartedAt) {

        public KeyState withWindow(LocalDateTime start, int count) {
            return new KeyState(keyId, scope, flowKey, start, count, lastProcessId, lastBusinessKey, lastStartedAt);
        }

        public KeyState withLastStart(String processId, String businessKey, LocalDateTime at) {
            return new KeyState(keyId, scope, flowKey, windowStart, windowCount, processId, businessKey, at);
        }
    }

    /** A start waiting for its moment. {@code absorbed}: how many starts a debounce folded into it. */
    record Pending(String id, String keyId, Kind kind, String workflowId, String requestJson, LocalDateTime dueAt,
                   LocalDateTime enqueuedAt, int absorbed) {

        public Pending withDueAt(LocalDateTime newDueAt) {
            return new Pending(id, keyId, kind, workflowId, requestJson, newDueAt, enqueuedAt, absorbed);
        }
    }

    /** One key, held. */
    interface KeyTx {
        KeyState state();

        void saveState(KeyState state);

        /** The key's pending debounce, if any. */
        Optional<Pending> debounce();

        /** The key's throttle queue, oldest first. */
        List<Pending> queued();

        /** Inserts or replaces a pending start. */
        void savePending(Pending pending);

        void deletePending(String id);
    }
}
