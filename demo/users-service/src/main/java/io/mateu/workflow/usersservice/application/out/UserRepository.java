package io.mateu.workflow.usersservice.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.usersservice.domain.aggregates.role.Role;
import io.mateu.workflow.usersservice.domain.aggregates.user.User;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;

public interface UserRepository extends Repository<User, UserId> {
}
