package io.mateu.workflow.contentservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.contentservice.application.query.LabelQueryService;
import io.mateu.workflow.contentservice.application.query.dto.LabelDto;
import io.mateu.workflow.contentservice.application.query.dto.LabelRow;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;


@Service
@RequiredArgsConstructor
public class LabelDBQueryService implements LabelQueryService {

final LabelEntityRepository repository;

private LabelRow toDomain(LabelEntity entity) {
return new LabelRow(
entity.id.toString(),
entity.name
);
}

@Override
public String getLabel(String id) {
return repository.findById(Long.valueOf(id)).map(LabelEntity::getName).orElse("Unknown");
}

@Override
public Optional<LabelDto> getById(String id) {
    return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private LabelDto toDto(LabelEntity entity) {
    return new LabelDto(
    entity.id.toString(),
    entity.name
    );
    }

    @Override
    public ListingData<LabelRow> findAll(String searchText,
        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
        .ofSize(pageable.size())
        .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
        page.getContent().stream().map(this::toDomain).toList()));
        }

        }