package io.mateu.workflow.usersservice.application.usecases.user.save;

public record SaveUserCommand(String id, String name, String email) {
}
