package io.mateu.workflow.usersservice.application.query.dto;

import java.util.List;

public record RoleDto(String id, String name, String description, List<String> permissionIds) {
}
