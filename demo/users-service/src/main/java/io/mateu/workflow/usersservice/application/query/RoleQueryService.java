package io.mateu.workflow.usersservice.application.query;

import io.mateu.workflow.usersservice.application.query.dto.RoleRow;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;

public interface RoleQueryService extends QueryService<RoleRow, RoleId> {
}
