package io.mateu.workflow.usersservice.application.out;

import io.mateu.workflow.usersservice.domain.aggregates.permission.Permission;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;

public interface PermissionRepository extends Repository<Permission, PermissionId> {
}
