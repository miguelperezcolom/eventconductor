package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.query.PermissionQueryService;
import io.mateu.workflow.usersservice.application.query.dto.PermissionRow;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
    public String getLabel(PermissionId id) {
        return repository.findById(id.id()).map(PermissionEntity::getName).orElse("Unknown permission");
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
