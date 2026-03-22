package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.query.UserQueryService;
import io.mateu.workflow.usersservice.application.query.dto.UserRow;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDBQueryService implements UserQueryService {

    final UserEntityRepository repository;

    private UserRow toDomain(UserEntity entity) {
        return new UserRow(
                entity.id,
                entity.name,
                entity.email
        );
    }

    @Override
    public String getLabel(UserId id) {
        return repository.findById(id.id()).map(UserEntity::getName).orElse("Unknown user");
    }

    @Override
    public ListingData<UserRow> findAll(String searchText,
                                        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}
