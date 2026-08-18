package io.mateu.testworker.domain;

import io.mateu.workflow.dtos.MessageType;

/**
 * A log line the simulated task emits, and when.
 *
 * @param type    {@code Info} or {@code Error}; an {@code Error} line lands on the process's
 *                Errors tab without the task itself having to fail, which is a state worth being
 *                able to produce on demand.
 * @param message the text, as the process log will show it.
 * @param atMs    milliseconds into the task, or null for "as it starts". A line scheduled past the
 *                task's duration still goes out, and the reply waits for it — that is deliberate,
 *                so a scenario cannot silently drop a line by mistiming it.
 */
public record LogLine(MessageType type, String message, Long atMs) {

    public LogLine(MessageType type, String message) {
        this(type, message, null);
    }

    public long offsetMs() {
        return atMs == null || atMs < 0 ? 0 : atMs;
    }
}
