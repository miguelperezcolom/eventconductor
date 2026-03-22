package io.mateu.workflow.usersservice.application.usecases.usergroup.create;

import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.UserGroup;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateUserGroupUseCase {

    final UserGroupRepository repository;

    @Transactional
    public void handle(CreateUserGroupCommand command) {
        repository.save(UserGroup.of(
                new UserGroupId(command.id()),
                new Name(command.name()),
                new Description(command.description())
        ));
    }

}
