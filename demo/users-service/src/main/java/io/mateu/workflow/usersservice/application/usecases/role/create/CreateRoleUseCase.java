package io.mateu.workflow.usersservice.application.usecases.role.create;

import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.Role;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateRoleUseCase {

    final RoleRepository repository;

    @Transactional
    public void handle(CreateRoleCommand command) {
        repository.save(Role.of(new RoleId(command.id()),
                new Name(command.name()),
                new Description(command.description()),
                command.permissionIds().stream().map(Long::valueOf).map(PermissionId::new).toList()
        ));
    }

}
