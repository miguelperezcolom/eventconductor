package io.mateu.workflow.usersservice.application.usecases.usergroup.delete;

import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteUserGroupUseCase {

    final UserGroupRepository repository;

    public void handle(DeleteUserGroupCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(UserGroupId::new)
                .toList());
    }

}
