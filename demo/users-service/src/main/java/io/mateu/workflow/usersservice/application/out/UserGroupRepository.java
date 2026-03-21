package io.mateu.workflow.usersservice.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.usersservice.domain.aggregates.user.User;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.UserGroup;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;

public interface UserGroupRepository extends Repository<UserGroup, UserGroupId> {
}
