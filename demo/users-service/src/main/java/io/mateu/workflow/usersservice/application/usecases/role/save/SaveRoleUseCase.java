package io.mateu.workflow.usersservice.application.usecases.role.save;

import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SaveRoleUseCase {

    final RoleRepository repository;

    @Transactional
    public void handle(SaveRoleCommand command) {
        var role = repository.findById(new RoleId(command.id())).orElseThrow();
        role.update(new Name(command.name()),
                        new Description(command.description()),
                command.permissionIds().stream()
                                .map(Long::valueOf)
                                .map(PermissionId::new).toList());
        repository.save(role);
    }

}
