package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.EnvironmentRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.Environment;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class EnvironmentDBRepository implements EnvironmentRepository {

final EnvironmentEntityRepository repository;

@Override
public Optional<Environment> findById(EnvironmentId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private Environment toDomain(EnvironmentEntity entity) {
    return new Environment(
    new EnvironmentId(entity.id),
    new EnvironmentName(entity.name)
    );
    }

    private EnvironmentEntity toEntity(Environment environment) {
    return new EnvironmentEntity(
environment.getId() != null?Long.valueOf(environment.getId().id()):null,
environment.getName().name()
    );
    }

    @Override
    public EnvironmentId save(Environment environment) {
    return new EnvironmentId(repository.save(toEntity(environment)).id);
    }

    @Override
    public void deleteAllById(List<EnvironmentId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(EnvironmentId::id).toList());
        }
        }
