package io.mateu.workflow.usersservice.application.usecases.usergroup.create;

import io.mateu.workflow.usersservice.application.out.UserRepository;
import io.mateu.workflow.usersservice.application.usecases.user.create.CreateUserCommand;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Email;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.user.User;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateUserGroupUseCase {

    final UserRepository repository;

    public void handle(CreateUserCommand command) {
        repository.save(User.of(
                new UserId(command.id()),
                new Name(command.name()),
                new Email(command.email())
        ));
    }

}
