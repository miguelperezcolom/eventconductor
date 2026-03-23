package io.mateu.workflow.usersservice.application.query;

import io.mateu.workflow.usersservice.application.query.dto.UserGroupDto;
import io.mateu.workflow.usersservice.application.query.dto.UserGroupRow;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;

public interface UserGroupQueryService extends QueryService<UserGroupDto, UserGroupRow, String> {
}
