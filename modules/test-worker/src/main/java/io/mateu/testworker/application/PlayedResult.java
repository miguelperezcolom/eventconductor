package io.mateu.testworker.application;

import io.mateu.testworker.domain.Outcome;

/**
 * What the simulator actually did, which is not always what the scenario asked for — a
 * cancellation can arrive first.
 *
 * @param outcome what was reported, or null when nothing was: the task was cancelled.
 * @param note    one line for the record, so the Received tasks page can say why a task that asked
 *                to complete never did.
 */
public record PlayedResult(Outcome outcome, String note) {

    public static PlayedResult nothing(String note) {
        return new PlayedResult(null, note);
    }
}
