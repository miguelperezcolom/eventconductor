package io.mateu.workflow.test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The answers a mocked task gives, in order — Mockito style: each {@code then…} adds one, the n-th
 * dispatch gets the n-th answer, and the last answer repeats for every dispatch after it.
 *
 * <pre>{@code
 * runtime.whenTask("charge-card")
 *         .thenFail("CARD_DECLINED", true)        // first attempt
 *         .thenComplete(Map.of("chargeId", "ch_1")); // the retry, and any later dispatch
 * }</pre>
 *
 * The count is per stub, across every process of the test.
 */
public final class TaskStub {

    private final String description;
    private final List<TaskAnswer> answers = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    TaskStub(String description) {
        this.description = description;
    }

    /** Completes, writing no variables. */
    public TaskStub thenComplete() {
        return thenAnswer(invocation -> TaskOutcome.complete());
    }

    /** Completes, writing {@code variables} onto the process (non-text values as JSON). */
    public TaskStub thenComplete(Map<String, ?> variables) {
        var copy = Map.copyOf(variables);
        return thenAnswer(invocation -> TaskOutcome.complete(copy));
    }

    /** Fails with {@code code}, retryably: the step spends its {@code retries}. */
    public TaskStub thenFail(String code) {
        return thenFail(code, true);
    }

    /** Fails with {@code code}; a non-retryable failure fails the step at once, retries or not. */
    public TaskStub thenFail(String code, boolean retryable) {
        return thenAnswer(invocation -> TaskOutcome.fail(code, retryable));
    }

    /** Never answers: the step stays in flight until its {@code timeout} fires under {@code advanceTime}. */
    public TaskStub thenTimeout() {
        return thenAnswer(invocation -> TaskOutcome.noReply());
    }

    /** Answers with whatever {@code answer} computes from the invocation. */
    public TaskStub thenAnswer(TaskAnswer answer) {
        answers.add(answer);
        return this;
    }

    /** How many dispatches this stub has answered. */
    public int calls() {
        return calls.get();
    }

    TaskOutcome answer(TaskInvocation invocation) {
        if (answers.isEmpty()) {
            throw new IllegalStateException(description + " is mocked but has no answer: add thenComplete(), "
                    + "thenFail(…), thenAnswer(…) or thenTimeout()");
        }
        var index = Math.min(calls.getAndIncrement(), answers.size() - 1);
        return answers.get(index).answer(invocation);
    }
}
