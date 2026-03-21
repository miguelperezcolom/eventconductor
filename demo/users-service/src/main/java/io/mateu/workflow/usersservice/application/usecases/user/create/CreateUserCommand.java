package io.mateu.workflow.usersservice.application.usecases.user.create;

public record CreateUserCommand(String id, String name, String email) {
}
