package io.mateu.workflow.application.usecases.completetask;

import io.mateu.workflow.domain.Value;

import java.util.List;

public record CompleteTaskCommand(String taskId, List<Value> values) {
}
