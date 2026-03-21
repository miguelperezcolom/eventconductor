package io.mateu.workflow.usersservice.application.usecases.permission.delete;

import java.util.List;

public record DeletePermissionCommand(List<String> ids) {
}
