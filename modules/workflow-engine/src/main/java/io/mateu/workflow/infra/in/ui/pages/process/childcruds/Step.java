package io.mateu.workflow.infra.in.ui.pages.process.childcruds;


import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Named;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One step execution as the process detail lists it.
 *
 * <p>Started and finished are on the row because they are what the list is scanned for once a
 * process is open: which step is taking the time, and how long the one still running has been at
 * it. The status alone says a step is RUNNING and not since when, which is the whole question when
 * an operator is deciding whether to wait or to intervene.
 *
 * @param started  when the step began, or empty while it has not — a step the process has created
 *                 but no worker has picked up yet.
 * @param finished when it reached a terminal status, or empty while it is still going.
 */
public record Step(@Hidden String processId, @Hidden String id, String name, Status status,
                   String started, String finished) implements Named {

    /** Same stamp the process listing uses, so the two read as one clock. */
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Formatted for the grid, or null when the moment has not arrived yet. */
    public static String format(LocalDateTime moment) {
        return moment != null ? moment.format(DTF) : null;
    }
}
