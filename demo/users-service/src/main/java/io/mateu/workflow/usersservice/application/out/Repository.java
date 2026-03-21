package io.mateu.workflow.usersservice.application.out;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;

import java.util.List;
import java.util.Optional;

public interface Repository<T, IdType> {
    Optional<T> findById(IdType id);

    IdType save(T entity);

    ListingData<T> findAll(String searchText,
                           Object filters, Pageable pageable);

    void deleteAllById(List<IdType> selectedIds);
}
