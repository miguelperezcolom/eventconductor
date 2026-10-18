package io.mateu.workflow.application.httpworkers;

/**
 * Wakes the long-poll dispatcher: something it may hand out appeared on this node (a task queued, a
 * lease given back). Its own tiny object, with no dependencies, so the engine's dispatch path can
 * reach it without depending on the worker API.
 */
public class HttpTaskSignal {

    private final Object monitor = new Object();
    private boolean raised;

    /** Raise the signal; never blocks. */
    public void raise() {
        synchronized (monitor) {
            raised = true;
            monitor.notifyAll();
        }
    }

    /**
     * Waits until the signal is raised or {@code millis} pass, and lowers it.
     *
     * @return whether it was raised
     */
    public boolean await(long millis) throws InterruptedException {
        synchronized (monitor) {
            if (!raised && millis > 0) {
                monitor.wait(millis);
            }
            var was = raised;
            raised = false;
            return was;
        }
    }
}
