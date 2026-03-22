package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.Permission;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.Role;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class RoleDBRepository implements RoleRepository {

    final RoleEntityRepository repository;

    @Override
    public Optional<Role> findById(RoleId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Role toDomain(RoleEntity entity) {
        return new Role(
                new RoleId(entity.id),
                new Name(entity.name),
                new Description(entity.description),
                listFromJson(entity.permissionsJson, String.class).stream()
                        .map(Long::valueOf)
                        .map(PermissionId::new).toList()
        );
    }

    private RoleEntity toEntity(Role permission) {
        return new RoleEntity(
                permission.getId().id(),
                permission.getName().name(),
                permission.getDescription().description(),
                toJson(permission.getPermissions().stream().map(PermissionId::id).toList())
        );
    }

    @Override
    public RoleId save(Role permission) {
        return new RoleId(repository.save(toEntity(permission)).id);
    }

    @Override
    public ListingData<Role> findAll(String searchText,
                                     Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page("", page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

    @Override
    public void deleteAllById(List<RoleId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(RoleId::id).toList());
    }
}
