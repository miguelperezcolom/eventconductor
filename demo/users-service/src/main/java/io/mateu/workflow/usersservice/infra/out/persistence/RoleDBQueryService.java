package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.query.RoleQueryService;
import io.mateu.workflow.usersservice.application.query.dto.RoleRow;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleDBQueryService implements RoleQueryService {

    final RoleEntityRepository repository;

    private RoleRow toDomain(RoleEntity entity) {
        return new RoleRow(
                entity.id,
                entity.name,
                entity.description
        );
    }

    @Override
    public String getLabel(RoleId id) {
        return repository.findById(id.id()).map(RoleEntity::getName).orElse("Unknown role");
    }

    @Override
    public ListingData<RoleRow> findAll(String searchText,
                                        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}
