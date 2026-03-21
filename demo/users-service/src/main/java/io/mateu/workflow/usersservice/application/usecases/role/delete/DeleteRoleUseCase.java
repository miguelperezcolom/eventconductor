package io.mateu.workflow.usersservice.application.usecases.role.delete;

import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteRoleUseCase {

    final RoleRepository repository;

    public void handle(DeleteRoleCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(RoleId::new)
                .toList());
    }

}
