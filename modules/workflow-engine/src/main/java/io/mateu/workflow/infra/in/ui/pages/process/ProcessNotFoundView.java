package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.EmptyState;

/**
 * What a link to a process that is no longer here renders.
 *
 * <p>Pasted links outlive the process they name — a retention sweep, a different environment, a
 * regenerated dataset — so this is an ordinary destination, not an exceptional one, and it has to
 * read like a page. The crud's own view toolbar already offers "Back to list", so the placeholder
 * carries no button of its own.
 *
 * <p>The title lives in {@code @Title} on purpose: without it the page is titled after the view
 * object's {@code toString()}, which is how "Process not found" used to reach the screen as
 * {@code Data[data={error=Process not found}, style=, cssClasses=, newState=null]}.
 */
@Title("Process not found")
public class ProcessNotFoundView {

  @Colspan(2)
  EmptyState notFound =
      EmptyState.builder()
          .icon("🔍")
          .title("Process not found")
          .description(
              "No process with that id. It may have been deleted, or the link may point at another"
                  + " environment.")
          .build();
}
