package io.mateu.workflow.controlplaneservice.application.usecases.site.delete;

import java.util.List;

public record DeleteSiteCommand(List<String> ids) {
    }
