package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.ReleaseQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseRow;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;


@Service
@RequiredArgsConstructor
public class ReleaseDBQueryService implements ReleaseQueryService {

final ReleaseEntityRepository repository;

private ReleaseRow toDomain(ReleaseEntity entity) {
return new ReleaseRow(
entity.id.toString(),
entity.name
);
}

@Override
public String getLabel(String id) {
return repository.findById(Long.valueOf(id)).map(ReleaseEntity::getName).orElse("Unknown");
}

@Override
public Optional<ReleaseDto> getById(String id) {
    return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private ReleaseDto toDto(ReleaseEntity entity) {
    return new ReleaseDto(
    entity.id.toString(),
    entity.name
    );
    }

    @Override
    public ListingData<ReleaseRow> findAll(String searchText,
        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
        .ofSize(pageable.size())
        .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
        page.getContent().stream().map(this::toDomain).toList()));
        }

        }