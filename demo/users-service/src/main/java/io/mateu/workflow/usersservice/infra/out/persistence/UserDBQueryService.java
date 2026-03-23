package io.mateu.workflow.usersservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.usersservice.application.query.UserQueryService;
import io.mateu.workflow.usersservice.application.query.dto.UserDto;
import io.mateu.workflow.usersservice.application.query.dto.UserRow;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;

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
    public String getLabel(String id) {
        return repository.findById(id).map(UserEntity::getName).orElse("Unknown user");
    }

    @Override
    public Optional<UserDto> getById(String id) {
        return repository.findById(id).map(entity -> new UserDto(
                entity.getId(),
                entity.getName(),
                entity.getEmail(),
                listFromJson(entity.getGroupsJson(), String.class),
                listFromJson(entity.getRolesJson(), String.class)
        ));
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
