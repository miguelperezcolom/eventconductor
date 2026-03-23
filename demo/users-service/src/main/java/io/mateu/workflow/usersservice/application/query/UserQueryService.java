package io.mateu.workflow.usersservice.application.query;

import io.mateu.workflow.usersservice.application.query.dto.UserDto;
import io.mateu.workflow.usersservice.application.query.dto.UserRow;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;

public interface UserQueryService extends QueryService<UserDto, UserRow, String> {
}
