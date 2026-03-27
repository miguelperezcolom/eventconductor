package io.mateu.workflow.controlplaneservice.application.usecases.page.delete;

import java.util.List;

public record DeletePageCommand(List<String> ids) {
}
