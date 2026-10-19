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
    PUBLISH_EVENT,

    /**
     * Call an HTTP endpoint ({@code http}: a named connection or an absolute URL, method, templated
     * path/query/headers/body, response → variables). The engine renders the request and dispatches it
     * to the built-in {@code http-call} worker task, so timeouts, retries, compensation and cancellation
     * work as for any worker step; a 4xx is not retried by default.
     */
    HTTP_CALL,

    /**
     * Ask a language model ({@code llm}: a named connection, templated system prompt and prompt, an
     * optional response schema, answer → variables). The engine renders the prompts and dispatches them
     * to the built-in {@code llm-call} worker task, so timeouts, retries, compensation and cancellation
     * work as for any worker step; a provider 4xx is not retried.
     */
    LLM_CALL,

    /**
     * Let a language model work towards a goal with tools ({@code agent}: a named connection, templated
     * system prompt and goal, an allowlist of tools, budgets). The step runs a loop: each model turn is a
     * child execution dispatched to the built-in {@code agent-turn} worker task, and each tool the model
     * calls is a child execution of the tool's own step — retried, timed out, cancelled and compensated
     * like any step — until the model answers or a budget is spent.
     */
    AGENT_CALL,

    /**
     * Ends this process — COMPLETED, outcome {@code continued} — and starts a fresh run of the same
     * definition with the same business key and the variables its {@code continueAsNew} block carries,
     * the two linked. Keeps the history of an entity- or subscription-style process bounded.
     */
    CONTINUE_AS_NEW;

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
