package io.mateu.workflow.controlplaneservice.application.usecases.asset.delete;

import java.util.List;

public record DeleteAssetCommand(List<String> ids) {
    }
