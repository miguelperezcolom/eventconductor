package io.mateu.workflow.usersservice.application.usecases.user.delete;

import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.application.out.UserRepository;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteUserUseCase {

    final UserRepository repository;

    public void handle(DeleteUserCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(UserId::new)
                .toList());
    }

}
