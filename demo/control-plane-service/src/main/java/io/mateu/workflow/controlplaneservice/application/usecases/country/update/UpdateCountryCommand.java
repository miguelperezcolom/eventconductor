package io.mateu.workflow.controlplaneservice.application.usecases.country.update;

public record UpdateCountryCommand(String code, String name, String tierId) {
}
