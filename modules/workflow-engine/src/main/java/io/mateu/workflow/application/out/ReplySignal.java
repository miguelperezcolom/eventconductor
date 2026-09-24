package io.mateu.workflow.application.out;

/**
 * Told when a process has just recorded its reply, so a caller waiting on this pod can be answered
 * at once rather than on the next poll.
 *
 * <p>Called after the save that recorded the reply, from inside its transaction when there is one:
 * implementations act on commit, never before — a waiter woken early would read a reply that is not
 * there yet (and, if the transaction rolls back, never will be).
 */
public interface ReplySignal {

    ReplySignal NONE = processId -> { };

    void replyRecorded(String processId);
}
