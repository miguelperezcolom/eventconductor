package io.mateu.workflow.contentservice.application.usecases.label.delete;

import java.util.List;

public record DeleteLabelCommand(List<String> ids) {
    }
