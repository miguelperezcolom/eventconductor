package io.mateu.workflow.controlplaneservice.application.usecases.tier.delete;

import java.util.List;

public record DeleteTierCommand(List<String> ids) {
}
