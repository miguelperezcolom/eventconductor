package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.query.PermissionQueryService;
import io.mateu.workflow.usersservice.application.query.dto.PermissionDto;
import io.mateu.workflow.usersservice.application.query.dto.PermissionRow;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PermissionDBQueryService implements PermissionQueryService {

    final PermissionEntityRepository repository;

    private PermissionRow toDomain(PermissionEntity entity) {
        return new PermissionRow(
                entity.id.toString(),
                entity.name,
                entity.description,
                entity.scope
        );
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(Long.valueOf(id)).map(PermissionEntity::getName).orElse("Unknown permission");
    }

    @Override
    public Optional<PermissionDto> getById(String id) {
        return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private PermissionDto toDto(PermissionEntity entity) {
        return new PermissionDto(
                entity.id.toString(),
                entity.name,
                entity.description,
                entity.scope
        );
    }

    @Override
    public ListingData<PermissionRow> findAll(String searchText,
                                        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}
