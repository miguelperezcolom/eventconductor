package io.mateu.workflow.application.out;

/**
 * The synchronous fast path: run a new process's consecutive engine-side steps on this pod, right
 * after it is created, instead of handing each transition to the relay and back.
 *
 * <p>Every transition is still written — with its outbox rows — exactly as on the normal path; the
 * only difference is who handles those rows. So nothing here can lose work: a pod that dies
 * mid-drive leaves claimed rows that the relay takes over once their lease lapses.
 */
public interface InlineExecution {

    /** No fast path (memory mode already runs everything inline; or it is switched off). */
    InlineExecution NONE = processId -> null;

    /**
     * Reserves a driving slot for a process about to be created, or returns null when there is none
     * free — the process then simply takes the normal path. Never blocks.
     */
    Slot reserve(String processId);

    /** One reserved drive. Exactly one of {@link #start} or {@link #abandon} must be called. */
    interface Slot {

        /** Runs the creation so that the rows it writes are claimed for this drive. */
        void claimDuring(Runnable creation);

        /** After the creation has committed: drive the process, off the caller's thread. */
        void start();

        /** The creation did not happen (rolled back, or a retry): give the slot back. */
        void abandon();
    }
}
