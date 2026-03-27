package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.AssetRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.Asset;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetPath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetUrl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssetDBRepository implements AssetRepository {

    final AssetEntityRepository repository;

    @Override
    public Optional<Asset> findById(AssetId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Asset toDomain(AssetEntity entity) {
        return new Asset(
                new AssetId(entity.id),
                new AssetName(entity.name),
                new AssetPath(entity.path),
                new AssetUrl(entity.url)
        );
    }

    private AssetEntity toEntity(Asset asset) {
        return new AssetEntity(
                asset.getId() != null ? asset.getId().id() : null,
                asset.getName().name(),
                asset.getPath().path(),
                asset.getUrl().url()
        );
    }

    @Override
    public AssetId save(Asset asset) {
        return new AssetId(repository.save(toEntity(asset)).id);
    }

    @Override
    public void deleteAllById(List<AssetId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(AssetId::id).toList());
    }
}
