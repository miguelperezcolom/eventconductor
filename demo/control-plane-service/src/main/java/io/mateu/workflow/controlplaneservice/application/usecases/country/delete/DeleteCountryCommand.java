package io.mateu.workflow.controlplaneservice.application.usecases.country.delete;

import java.util.List;

public record DeleteCountryCommand(List<String> ids) {
}
