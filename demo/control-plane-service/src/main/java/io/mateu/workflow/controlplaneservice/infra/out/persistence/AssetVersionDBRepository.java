package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.AssetVersionRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.AssetVersion;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class AssetVersionDBRepository implements AssetVersionRepository {

final AssetVersionEntityRepository repository;

@Override
public Optional<AssetVersion> findById(AssetVersionId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private AssetVersion toDomain(AssetVersionEntity entity) {
    return new AssetVersion(
    new AssetVersionId(entity.id),
    new AssetVersionName(entity.name)
    );
    }

    private AssetVersionEntity toEntity(AssetVersion assetversion) {
    return new AssetVersionEntity(
assetversion.getId() != null?Long.valueOf(assetversion.getId().id()):null,
assetversion.getName().name()
    );
    }

    @Override
    public AssetVersionId save(AssetVersion assetversion) {
    return new AssetVersionId(repository.save(toEntity(assetversion)).id);
    }

    @Override
    public void deleteAllById(List<AssetVersionId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(AssetVersionId::id).toList());
        }
        }
