package io.mateu.workflow.usersservice.application.usecases.user.delete;

import java.util.List;

public record DeleteUserCommand(List<String> ids) {
}
