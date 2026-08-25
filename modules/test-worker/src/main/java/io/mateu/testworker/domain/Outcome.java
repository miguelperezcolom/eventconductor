package io.mateu.testworker.domain;

/**
 * How a simulated task ends.
 *
 * <p>Three values rather than two, because "the worker never answered" is a distinct thing to
 * test: it is what the engine's step timeout exists for, and no combination of COMPLETED and ERROR
 * produces it.
 */
public enum Outcome {

    /** Reports {@code COMPLETED} with the scenario's variables. */
    COMPLETED,

    /** Reports {@code ERROR}, preceded by the reason as an {@code Error} log line. */
    ERROR,

    /**
     * Reports {@code RUNNING} and then goes quiet — a worker that took the task and hung.
     *
     * <p>{@code RUNNING} is sent on purpose: a worker that is silent from the first byte is a
     * worker that never received the task, which the engine cannot tell from a broker that lost
     * it. Starting and then stopping is the scenario the step timeout is written for, and it is
     * the one worth being able to reproduce.
     */
    NO_REPLY
}
