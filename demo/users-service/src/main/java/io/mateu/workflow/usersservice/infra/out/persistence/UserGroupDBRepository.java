package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.Permission;
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
    public UserGroupId save(UserGroup permission) {
        return new UserGroupId(repository.save(toEntity(permission)).id);
    }

    @Override
    public ListingData<UserGroup> findAll(String searchText,
                                          Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page("", page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

    @Override
    public void deleteAllById(List<UserGroupId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(UserGroupId::id).toList());
    }
}
