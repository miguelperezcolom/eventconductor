package io.mateu.formse2e.support;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The application's own worker: it answers nothing, and records what reached it. */
public class RecordingTaskExecutor implements EmbeddedTaskExecutor {

    public final List<TaskExecutionRequested> received = new CopyOnWriteArrayList<>();

    @Override
    public void execute(TaskExecutionRequested request) {
        received.add(request);
    }
}
