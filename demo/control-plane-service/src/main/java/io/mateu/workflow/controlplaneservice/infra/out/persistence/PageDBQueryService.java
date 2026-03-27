package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.PageQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.PageDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.PageRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class PageDBQueryService implements PageQueryService {

    final PageEntityRepository repository;

    private PageRow toDomain(PageEntity entity) {
        return new PageRow(
                entity.id.toString(),
                entity.siteId,
                entity.name
        );
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(Long.valueOf(id)).map(PageEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<PageDto> getById(String id) {
        return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private PageDto toDto(PageEntity entity) {
        return new PageDto(
                entity.id.toString(),
                entity.siteId,
                entity.name,
                entity.path
        );
    }

    @Override
    public ListingData<PageRow> findAll(String searchText,
                                        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}