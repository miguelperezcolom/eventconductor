package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;

/**
 * A row of the processes listing. The id is labelled on purpose: Mateu leaves an unlabelled id out of
 * a listing whose rows open on click (it is technical there), but an operator copies a process id
 * into a search, a log query or a ticket, so this console keeps it as its first column.
 *
 * <p>The business key beside the name: it is the identifier the business knows a run by — the order
 * number, the booking reference — and what an operator looking for one run scans for.
 */
public record ProcessRow(@Label("Id") String id, String name, @Label("Business key") String businessKey, Status status,
                         String created, String started, String finished) implements Identifiable {
}
