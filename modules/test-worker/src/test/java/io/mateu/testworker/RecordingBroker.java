package io.mateu.testworker;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.util.MimeType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what the worker reports back, and accepts it the way a healthy broker would. */
public class RecordingBroker implements StreamOperations {

    public final List<DomainEvent> sent = new CopyOnWriteArrayList<>();

    @Override
    public boolean send(String bindingName, Object data) {
        if (data instanceof DomainEvent event) {
            sent.add(event);
        }
        return true;
    }

    @Override
    public boolean send(String bindingName, Object data, MimeType outputContentType) {
        return send(bindingName, data);
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data) {
        return send(bindingName, data);
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data,
                        MimeType outputContentType) {
        return send(bindingName, data);
    }

    public List<TaskStatusChanged> statuses() {
        return sent.stream().filter(TaskStatusChanged.class::isInstance)
                .map(TaskStatusChanged.class::cast).toList();
    }

    public List<TaskStatus> statusValues() {
        return statuses().stream().map(TaskStatusChanged::status).toList();
    }

    public List<TaskLogEmitted> logs() {
        return sent.stream().filter(TaskLogEmitted.class::isInstance)
                .map(TaskLogEmitted.class::cast).toList();
    }
}
