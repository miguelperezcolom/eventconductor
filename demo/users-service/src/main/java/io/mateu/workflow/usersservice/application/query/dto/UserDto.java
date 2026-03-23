package io.mateu.workflow.usersservice.application.query.dto;

import java.util.List;

public record UserDto(String id, String name, String email, List<String> groupIds, List<String> roleIds) {
}
