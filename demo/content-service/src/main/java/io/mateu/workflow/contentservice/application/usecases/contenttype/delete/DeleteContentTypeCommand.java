package io.mateu.workflow.contentservice.application.usecases.contenttype.delete;

import java.util.List;

public record DeleteContentTypeCommand(List<String> ids) {
    }
