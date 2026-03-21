package io.mateu.workflow.usersservice.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.Permission;
import io.mateu.workflow.usersservice.domain.aggregates.role.Role;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;

public interface RoleRepository extends Repository<Role, RoleId> {
}
