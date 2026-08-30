package io.mateu.testworker.application;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.testworker.domain.TaskOverride;

import java.util.List;

/**
 * Where the editable replies live. Extends Mateu's {@code CrudStore} for the UI that edits them;
 * the worker reads them to resolve a task, and — via {@code DefaultScenarioSeeder} — writes one
 * back the first time it meets a task it had no instructions for, so a run leaves a row per kind of
 * task it saw, ready to be retouched.
 */
public interface TaskOverrideStore extends CrudStore<TaskOverride> {

    /** The rows worth matching against. Disabled rows stay stored and are never returned here. */
    List<TaskOverride> enabled();
}
