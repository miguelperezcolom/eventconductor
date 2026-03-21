package io.mateu.workflow.usersservice.application.usecases.permission.save;

import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SavePermissionUseCase {

    final PermissionRepository repository;

    public void handle(SavePermissionCommand command) {
        var permission = repository.findById(new PermissionId(Long.valueOf(command.id()))).orElseThrow();
        repository.save(permission
                .withName(new Name(command.name()))
                .withDescription(new Description(command.description()))
        );
    }

}
