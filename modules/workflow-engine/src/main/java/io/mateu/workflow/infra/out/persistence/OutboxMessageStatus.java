package io.mateu.workflow.infra.out.persistence;

public enum OutboxMessageStatus {
    Pending, Sent, Error,
    /**
     * Written already claimed by the pod driving its process inline (the synchronous fast path):
     * no relay ever sees it — the relays only claim {@code Pending}. It becomes {@code Sent} when
     * that pod has handled it, or {@code Pending} again when the pod stops driving (the rest goes
     * back to the normal path) or its claim lease expires (the pod died).
     */
    InlineClaimed
}
