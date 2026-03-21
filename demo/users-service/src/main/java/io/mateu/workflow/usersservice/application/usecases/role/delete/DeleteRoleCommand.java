package io.mateu.workflow.usersservice.application.usecases.role.delete;

import java.util.List;

public record DeleteRoleCommand(List<String> ids) {
}
