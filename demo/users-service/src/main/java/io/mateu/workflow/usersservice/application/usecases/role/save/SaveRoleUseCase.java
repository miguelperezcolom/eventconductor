package io.mateu.workflow.usersservice.application.usecases.role.save;

import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SaveRoleUseCase {

    final RoleRepository repository;

    public void handle(SaveRoleCommand command) {
        var role = repository.findById(new RoleId(command.id())).orElseThrow();
        repository.save(role
                .withName(new Name(command.name()))
                .withDescription(new Description(command.description()))
                .withPermissions(command.permissionIds().stream()
                        .map(Long::valueOf)
                        .map(PermissionId::new).toList())
        );
    }

}
