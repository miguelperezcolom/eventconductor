package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.Permission;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PermissionDBRepository implements PermissionRepository {

    final PermissionEntityRepository repository;

    @Override
    public Optional<Permission> findById(PermissionId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Permission toDomain(PermissionEntity permissionEntity) {
        return new Permission(
                new PermissionId(permissionEntity.id),
                new Name(permissionEntity.name),
                new Description(permissionEntity.description)
        );
    }

    private PermissionEntity toEntity(Permission permission) {
        return new PermissionEntity(
                permission.getId() != null?Long.valueOf(permission.getId().id()):null,
                permission.getName().name(),
                permission.getDescription().description()
        );
    }

    @Override
    public PermissionId save(Permission permission) {
        return new PermissionId(repository.save(toEntity(permission)).id);
    }

    @Override
    public ListingData<Permission> findAll(String searchText,
                                           Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page("", page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

    @Override
    public void deleteAllById(List<PermissionId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(PermissionId::id).toList());
    }
}
