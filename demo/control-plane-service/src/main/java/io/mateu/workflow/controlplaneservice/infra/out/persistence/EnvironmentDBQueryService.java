package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.EnvironmentQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.EnvironmentDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.EnvironmentRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class EnvironmentDBQueryService implements EnvironmentQueryService {

    final EnvironmentEntityRepository repository;

    private EnvironmentRow toDomain(EnvironmentEntity entity) {
        return new EnvironmentRow(
                entity.id.toString(),
                entity.name
        );
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(id).map(EnvironmentEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<EnvironmentDto> getById(String id) {
        return repository.findById(id).map(this::toDto);
    }

    private EnvironmentDto toDto(EnvironmentEntity entity) {
        return new EnvironmentDto(
                entity.id.toString(),
                entity.name
        );
    }

    @Override
    public ListingData<EnvironmentRow> findAll(String searchText,
                                               Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}