package io.mateu.workflow.infra.in.ui.pages.process.childcruds;

import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.interfaces.Identifiable;

import java.time.LocalDateTime;

/**
 * One update of a process, as the Updates tab lists it: when, which, who asked, how it ended, what it
 * carried (sensitive fields masked) and what it answered — or why it was refused.
 */
public record UpdateEntry(@HiddenInList String processId, @HiddenInList String id, LocalDateTime time, String update,
                          String caller, String status, String payload, String result) implements Identifiable {
}
