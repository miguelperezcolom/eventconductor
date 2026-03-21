package io.mateu.workflow.usersservice.application.usecases.permission.create;

import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.Permission;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreatePermissionUseCase {

    final PermissionRepository repository;

    public String handle(CreatePermissionCommand command) {
        return repository.save(Permission.of(new Name(command.name()), new Description(command.description()))).id().toString();
    }

}
