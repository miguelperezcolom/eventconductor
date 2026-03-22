package io.mateu.workflow.usersservice.application.usecases.role.delete;

import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteRoleUseCase {

    final RoleRepository repository;

    @Transactional
    public void handle(DeleteRoleCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(RoleId::new)
                .toList());
    }

}
