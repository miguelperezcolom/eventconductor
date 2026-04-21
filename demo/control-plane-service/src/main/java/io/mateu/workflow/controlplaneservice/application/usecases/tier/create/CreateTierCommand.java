package io.mateu.workflow.controlplaneservice.application.usecases.tier.create;

public record CreateTierCommand(String id, String name, int parallelThreads) {
}
