package io.mateu.workflow.usersservice.application.usecases.user.create;

import java.util.List;

public record CreateUserCommand(String id, String name, String email, List<String> groupIds, List<String> roleIds) {

    public CreateUserCommand(String id, String name, String email, List<String> groupIds, List<String> roleIds) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.groupIds = groupIds != null ? groupIds : List.of();
        this.roleIds = roleIds != null ? roleIds : List.of();
    }
}
