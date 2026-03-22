package io.mateu.workflow.usersservice.application.usecases.role.save;

import java.util.List;

public record SaveRoleCommand(String id, String name, String description, List<String> permissionIds) {

    public SaveRoleCommand {
        if (permissionIds == null) permissionIds = List.of();
    }

}
