package io.mateu.workflow.usersservice.application.usecases.permission.save;

public record SavePermissionCommand(String id, String name, String description, String scope) {
}
