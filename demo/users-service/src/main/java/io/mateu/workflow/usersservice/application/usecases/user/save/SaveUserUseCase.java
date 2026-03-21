package io.mateu.workflow.usersservice.application.usecases.user.save;

import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.application.out.UserRepository;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Email;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SaveUserUseCase {

    final UserRepository repository;

    public void handle(SaveUserCommand command) {
        var role = repository.findById(new UserId(command.id())).orElseThrow();
        repository.save(role
                .withName(new Name(command.name()))
                .withEmail(new Email(command.email()))
        );
    }

}
