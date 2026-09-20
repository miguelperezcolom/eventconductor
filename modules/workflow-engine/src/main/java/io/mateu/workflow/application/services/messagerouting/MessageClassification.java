package io.mateu.workflow.application.services.messagerouting;

/**
 * How a message name is routed across shards, derived from the {@code WAIT_FOR_MESSAGE} steps that
 * wait for it (see {@link MessageClassifier}). The classification only chooses the <em>primary</em>
 * routing path; the shard that receives a routed message still runs the normal correlation, so a
 * wrong guess costs an extra query or a broadcast, never a lost message.
 */
public enum MessageClassification {

    /**
     * Every waiter uses the default correlation (the process business key), so the message can be
     * routed to the shard that key is placed on (layer 1), falling through to the subscription table
     * for keys that are not placed (e.g. child processes).
     */
    BUSINESS_KEY,

    /**
     * At least one waiter correlates by a {@code correlationExpression}, so the key is not a business
     * key and cannot be placed; routed via the subscription table (layer 2), else broadcast.
     */
    EXPRESSION,

    /**
     * A waiter opted out of routing for this message (the step's broadcast attribute); always
     * broadcast. Contributed in phase 2 with the step attribute.
     */
    BROADCAST,

    /** No imported step waits for this name; nothing to route to (broadcast, harmlessly). */
    UNKNOWN
}
