package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class ReleaseDBRepository implements ReleaseRepository {

final ReleaseEntityRepository repository;

@Override
public Optional<Release> findById(ReleaseId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private Release toDomain(ReleaseEntity entity) {
    return new Release(
    new ReleaseId(entity.id),
    new ReleaseName(entity.name)
    );
    }

    private ReleaseEntity toEntity(Release release) {
    return new ReleaseEntity(
release.getId() != null?Long.valueOf(release.getId().id()):null,
release.getName().name()
    );
    }

    @Override
    public ReleaseId save(Release release) {
    return new ReleaseId(repository.save(toEntity(release)).id);
    }

    @Override
    public void deleteAllById(List<ReleaseId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(ReleaseId::id).toList());
        }
        }
