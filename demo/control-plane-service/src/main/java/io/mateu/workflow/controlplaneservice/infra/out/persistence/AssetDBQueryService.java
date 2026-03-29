package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.AssetQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class AssetDBQueryService implements AssetQueryService {

    final AssetEntityRepository repository;

    private AssetRow toDomain(AssetEntity entity) {
        return new AssetRow(
                entity.id.toString(),
                entity.name
        );
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(Long.valueOf(id)).map(AssetEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<AssetDto> getById(String id) {
        return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private AssetDto toDto(AssetEntity entity) {
        return new AssetDto(
                entity.id.toString(),
                entity.name,
                entity.path, entity.url,
                entity.countryCode
        );
    }

    @Override
    public ListingData<AssetRow> findAll(String searchText,
                                         Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}