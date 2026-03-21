package io.mateu.workflow.usersservice.application.usecases.usergroup.delete;

import java.util.List;

public record DeleteUserGroupCommand(List<String> ids) {
}
