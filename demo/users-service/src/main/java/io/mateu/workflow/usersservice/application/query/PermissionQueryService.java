package io.mateu.workflow.usersservice.application.query;

import io.mateu.workflow.usersservice.application.query.dto.PermissionRow;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;

public interface PermissionQueryService extends QueryService<PermissionRow, PermissionId> {
}
