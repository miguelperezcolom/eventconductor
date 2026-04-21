package io.mateu.workflow.controlplaneservice.application.usecases.tier.update;

public record UpdateTierCommand(String id, String name, int parallelThreads) {
}
