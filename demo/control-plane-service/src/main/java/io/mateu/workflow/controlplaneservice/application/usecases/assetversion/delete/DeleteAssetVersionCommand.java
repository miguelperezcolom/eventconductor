package io.mateu.workflow.controlplaneservice.application.usecases.assetversion.delete;

import java.util.List;

public record DeleteAssetVersionCommand(List<String> ids) {
    }
