package io.mateu.testworker.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.NotCreatable;
import io.mateu.uidl.annotations.NotEditable;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.domain.ReceivedTask;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * What the worker has been asked to do, newest first.
 *
 * <p>Not editable and not creatable: this is the record of what happened, and a page that lets you
 * add to it is a page that lets you debug a run that never occurred. Changing what happens
 * <em>next</em> is {@link TaskOverrides}, which is a page away.
 *
 * <p>This carried a toolbar button that turned the task you were looking at into an override for
 * its step — the "I saw this go wrong, now make it go wrong on purpose" move. It is gone, because
 * it did not render: a {@code @NotEditable} Crud offers no detail toolbar for a
 * {@code @ViewToolbarButton} to sit on, and the browser test is what found that out. Rather than
 * make the history editable to get a button back, the shortcut is dropped and the two-step route —
 * read the step id here, create the override there — is the one the documentation describes.
 */
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
@NotCreatable
@NotEditable
public class ReceivedTasks extends AutoCrud<ReceivedTask> {

    final ReceivedTaskStore receivedTaskStore;

    @Override
    public CrudStore<ReceivedTask> store() {
        return receivedTaskStore;
    }
}
