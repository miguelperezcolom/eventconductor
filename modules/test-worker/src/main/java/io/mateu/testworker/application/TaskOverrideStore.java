package io.mateu.testworker.application;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.testworker.domain.TaskOverride;

import java.util.List;

/**
 * Where the hand-edited replies live. Extends Mateu's {@code CrudStore} because the UI is the
 * only thing that writes to it — the worker only ever reads.
 */
public interface TaskOverrideStore extends CrudStore<TaskOverride> {

    /** The rows worth matching against. Disabled rows stay stored and are never returned here. */
    List<TaskOverride> enabled();
}
