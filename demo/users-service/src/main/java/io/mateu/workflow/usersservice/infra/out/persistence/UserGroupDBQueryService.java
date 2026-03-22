package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.query.UserGroupQueryService;
import io.mateu.workflow.usersservice.application.query.dto.UserGroupRow;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserGroupDBQueryService implements UserGroupQueryService {

    final UserGroupEntityRepository repository;

    private UserGroupRow toDomain(UserGroupEntity entity) {
        return new UserGroupRow(
                entity.id,
                entity.name,
                entity.description
        );
    }

    @Override
    public String getLabel(UserGroupId id) {
        return repository.findById(id.id()).map(UserGroupEntity::getName).orElse("Unknown user group");
    }

    @Override
    public ListingData<UserGroupRow> findAll(String searchText,
                                        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}
