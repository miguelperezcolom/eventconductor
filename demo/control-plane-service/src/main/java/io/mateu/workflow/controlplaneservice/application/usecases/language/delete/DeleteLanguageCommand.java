package io.mateu.workflow.controlplaneservice.application.usecases.language.delete;

import java.util.List;

public record DeleteLanguageCommand(List<String> ids) {
    }
