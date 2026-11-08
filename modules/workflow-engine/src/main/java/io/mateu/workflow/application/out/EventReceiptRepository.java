package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.EventReceipt;

import java.time.Instant;
import java.util.List;

/**
 * The receipts of inbound domain events: which receiver already used which event (idempotency), and
 * the recent ones for the listing. In memory for {@code workflow.persistence=memory}, the
 * {@code inbound_event_receipt} table for jpa.
 */
public interface EventReceiptRepository {

    /** Whether a receipt with this key exists. */
    boolean exists(String receiptKey);

    /** Records a receipt; one that already exists under its key is left as it is. */
    void save(EventReceipt receipt);

    /** The most recent receipts first, at most {@code limit}; only one workflow's when it is not null. */
    List<EventReceipt> recent(int limit, String workflowId);

    /** The receipts of one flow-control key, most recent first, at most {@code limit}; only one workflow's when it is not null. */
    List<EventReceipt> recentOfFlowKey(int limit, String workflowId, String flowKey);

    /** Deletes the receipts received before {@code cutoff}; returns how many. */
    int purgeReceivedBefore(Instant cutoff);
}
