package io.mateu.workflow.usersservice.application.usecases.usergroup.save;

import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SaveUserGroupUseCase {

    final UserGroupRepository repository;

    @Transactional
    public void handle(SaveUserGroupCommand command) {
        var userGroup = repository.findById(new UserGroupId(command.id())).orElseThrow();
        userGroup.update(new Name(command.name()),
                new Description(command.description()));
        repository.save(userGroup);
    }

}
