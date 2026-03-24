package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.AssetVersionQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetVersionDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetVersionRow;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;


@Service
@RequiredArgsConstructor
public class AssetVersionDBQueryService implements AssetVersionQueryService {

final AssetVersionEntityRepository repository;

private AssetVersionRow toDomain(AssetVersionEntity entity) {
return new AssetVersionRow(
entity.id.toString(),
entity.name
);
}

@Override
public String getLabel(String id) {
return repository.findById(Long.valueOf(id)).map(AssetVersionEntity::getName).orElse("Unknown");
}

@Override
public Optional<AssetVersionDto> getById(String id) {
    return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private AssetVersionDto toDto(AssetVersionEntity entity) {
    return new AssetVersionDto(
    entity.id.toString(),
    entity.name
    );
    }

    @Override
    public ListingData<AssetVersionRow> findAll(String searchText,
        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
        .ofSize(pageable.size())
        .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
        page.getContent().stream().map(this::toDomain).toList()));
        }

        }