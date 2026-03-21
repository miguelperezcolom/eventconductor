package io.mateu.workflow.usersservice.application.usecases.usergroup.save;

import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SaveUserGroupUseCase {

    final UserGroupRepository repository;

    public void handle(SaveUserGroupCommand command) {
        var role = repository.findById(new UserGroupId(command.id())).orElseThrow();
        repository.save(role
                .withName(new Name(command.name()))
                .withDescription(new Description(command.description()))
        );
    }

}
