package io.mateu.workflow.contentservice.application.usecases.content.delete;

import java.util.List;

public record DeleteContentCommand(List<String> ids) {
    }
