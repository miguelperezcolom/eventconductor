package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum StepType {

    START, ACTION, JOIN, FORK, CHOICE, END, USER_TASK, PROCESS, TIMER, WAIT_FOR_MESSAGE, SEND_MESSAGE, RULE, DYNAMIC,

    /**
     * Acquire a named per-key lock ({@code lockName} + {@code lockKey}), serializing every process
     * or critical section that targets the same key. The step completes the moment it holds the
     * lock; if another process holds it, this one parks in {@code WAITING_ON_LOCK} and is admitted
     * FIFO when the lock frees. Released by a matching {@link #UNLOCK} step or when the process ends.
     */
    LOCK,

    /** Release a lock taken by a {@link #LOCK} step, admitting the next waiter for that key. */
    UNLOCK,

    /**
     * Emit the process's reply — from {@code replyVariables} or a {@code replyExpression} — to
     * whoever invoked it synchronously. Engine-internal: it completes in the step-over that starts
     * it, and the flow carries on. A process replies at most once; placed before END it answers
     * with the result, placed earlier the caller gets its answer while the process continues.
     */
    REPLY,

    /**
     * Publish a domain event for the outside world from the process's state ({@code event}: a
     * logical destination, a type, a key and a payload template). Engine-internal: written to the
     * outbox in the transaction that completes the step — published if and only if the step
     * completed — and relayed to the destination's topic (kafka) or the application's publisher
     * (embedded).
     */
    PUBLISH_EVENT;

    /**
     * Accepts the pre-rename alias {@code MESSAGE} (now {@code WAIT_FOR_MESSAGE}) so that the
     * persisted stepJson of in-flight processes and old definition files keep deserializing.
     */
    @JsonCreator
    public static StepType fromJson(String value) {
        if ("MESSAGE".equals(value)) {
            return WAIT_FOR_MESSAGE;
        }
        return valueOf(value);
    }

}
