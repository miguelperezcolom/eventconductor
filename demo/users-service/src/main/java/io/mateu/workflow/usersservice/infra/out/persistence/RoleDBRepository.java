package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.workflow.usersservice.application.out.RoleRepository;
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

    private RoleEntity toEntity(Role role) {
        return new RoleEntity(
                role.getId().id(),
                role.getName().name(),
                role.getDescription().description(),
                toJson(role.getPermissions().stream().map(PermissionId::id).toList())
        );
    }

    @Override
    public RoleId save(Role role) {
        return new RoleId(repository.save(toEntity(role)).id);
    }

    @Override
    public void deleteAllById(List<RoleId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(RoleId::id).toList());
    }
}
