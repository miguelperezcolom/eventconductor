package io.mateu.workflow.infra.in.ui.pages.process.childcruds;


import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.interfaces.Identifiable;

import java.time.LocalDateTime;

/**
 * An error line of a process — or of a run it started: {@code step} says where it was raised, and for a
 * child run it names the parent's step that started it ({@code rooms · Room 900 › book-room}), so the
 * reason a forEach iteration failed is read on the parent, where the operator is looking.
 */
public record Error(@HiddenInList String processId, @HiddenInList String id, LocalDateTime time,
                    @Label("Step") String step, String message) implements Identifiable {
}
