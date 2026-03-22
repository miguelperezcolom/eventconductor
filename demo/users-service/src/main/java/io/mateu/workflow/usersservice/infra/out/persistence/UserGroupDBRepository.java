package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Status;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.UserGroup;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserGroupDBRepository implements UserGroupRepository {

    final UserGroupEntityRepository repository;

    @Override
    public Optional<UserGroup> findById(UserGroupId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private UserGroup toDomain(UserGroupEntity entity) {
        return new UserGroup(
                new UserGroupId(entity.id),
                new Name(entity.name),
                new Description(entity.description),
                Status.valueOf(entity.status)
        );
    }

    private UserGroupEntity toEntity(UserGroup userGroup) {
        return new UserGroupEntity(
                userGroup.getId().id(),
                userGroup.getName().name(),
                userGroup.getDescription().description(),
                userGroup.getStatus().name()
        );
    }

    @Override
    public UserGroupId save(UserGroup userGroup) {
        return new UserGroupId(repository.save(toEntity(userGroup)).id);
    }

    @Override
    public void deleteAllById(List<UserGroupId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(UserGroupId::id).toList());
    }
}
