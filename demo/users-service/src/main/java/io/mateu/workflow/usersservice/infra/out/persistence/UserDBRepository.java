package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.workflow.usersservice.application.out.UserRepository;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Email;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Status;
import io.mateu.workflow.usersservice.domain.aggregates.user.User;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserDBRepository implements UserRepository {

    final UserEntityRepository repository;

    @Override
    public Optional<User> findById(UserId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private User toDomain(UserEntity entity) {
        return new User(
                new UserId(entity.id),
                new Name(entity.name),
                new Email(entity.email),
                "",
                "",
                Status.valueOf(entity.status)
        );
    }

    private UserEntity toEntity(User User) {
        return new UserEntity(
                User.getId().id(),
                User.getName().name(),
                User.getEmail().email(),
                "",
                "",
                User.getStatus().name()
        );
    }

    @Override
    public UserId save(User permission) {
        return new UserId(repository.save(toEntity(permission)).id);
    }

    @Override
    public List<User> findAll() {
        return repository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void deleteAllById(List<UserId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(UserId::id).toList());
    }
}
