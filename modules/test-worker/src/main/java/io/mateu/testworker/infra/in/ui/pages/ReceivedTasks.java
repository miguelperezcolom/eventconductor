package io.mateu.testworker.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.NotCreatable;
import io.mateu.uidl.annotations.NotEditable;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.application.TaskOverrideStore;
import io.mateu.testworker.domain.ReceivedTask;
import io.mateu.testworker.domain.TaskOverride;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * What the worker has been asked to do, newest first.
 *
 * <p>Not editable and not creatable: this is the record of what happened. The way to change what
 * happens next is the toolbar button, which turns the task you are looking at into an override for
 * the next run of the same task — the "I saw this go wrong, now make it go wrong on purpose" move,
 * which is the one worth making cheap.
 */
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
@NotCreatable
@NotEditable
@Action(id = "action-on-view-overrideNextRuns")
public class ReceivedTasks extends AutoCrud<ReceivedTask> {

    final ReceivedTaskStore receivedTaskStore;
    final TaskOverrideStore taskOverrideStore;
    final ObjectProvider<TaskOverrides> taskOverrides;

    @Override
    public CrudStore<ReceivedTask> store() {
        return receivedTaskStore;
    }

    /**
     * Creates an enabled override matching this task's step, prefilled with what was just played,
     * and opens the overrides page on it.
     *
     * <p>Enabled, not disabled. The button says it overrides the next runs, and a row that quietly
     * does nothing until someone finds the checkbox would be a worse surprise than the one it
     * avoids. It matches by workflow definition and step, so it changes that step and nothing
     * else — and any process carrying its own {@code TEST_CONFIG} ignores it regardless.
     */
    @ViewToolbarButton
    public TaskOverrides overrideNextRuns(HttpRequest httpRequest) {
        var task = httpRequest.getComponentState(ReceivedTask.class);
        taskOverrideStore.save(new TaskOverride(
                null,
                "%s (from %s)".formatted(task.taskId(), task.processId()),
                task.workflowDefinitionId(),
                task.stepId(),
                null,
                true,
                task.durationMs(),
                task.outcome(),
                null,
                null,
                null,
                false,
                List.of(),
                List.of()));
        return taskOverrides.getObject();
    }

    @Override
    public Object handleAction(String actionId, HttpRequest httpRequest) {
        if ("action-on-view-overrideNextRuns".equals(actionId)) {
            return overrideNextRuns(httpRequest);
        }
        return super.handleAction(actionId, httpRequest);
    }
}
