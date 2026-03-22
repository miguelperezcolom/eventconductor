package io.mateu.workflow.usersservice.application.out;

import io.mateu.workflow.usersservice.domain.aggregates.usergroup.UserGroup;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;

public interface UserGroupRepository extends Repository<UserGroup, UserGroupId> {
}
