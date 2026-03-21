package io.mateu.workflow.usersservice.application.usecases.permission.delete;

import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeletePermissionUseCase {

    final PermissionRepository repository;

    public void handle(DeletePermissionCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(Long::valueOf)
                .map(PermissionId::new)
                .toList());
    }

}
