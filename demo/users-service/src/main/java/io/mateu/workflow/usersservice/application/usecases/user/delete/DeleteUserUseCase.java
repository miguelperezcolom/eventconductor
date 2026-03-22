package io.mateu.workflow.usersservice.application.usecases.user.delete;

import io.mateu.workflow.usersservice.application.out.UserRepository;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteUserUseCase {

    final UserRepository repository;

    @Transactional
    public void handle(DeleteUserCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(UserId::new)
                .toList());
    }

}
