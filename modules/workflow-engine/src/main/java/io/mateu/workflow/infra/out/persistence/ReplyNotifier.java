package io.mateu.workflow.infra.out.persistence;

/**
 * Tells the other nodes on this database that a process has just recorded its reply, from inside
 * the transaction that records it — so the notice is exactly as durable as the reply: delivered on
 * commit, never on rollback. The pod waiting for that reply may be any of them.
 */
public interface ReplyNotifier {

    String CHANNEL = "ec_sync_reply";

    void replyRecorded(String processId);
}
