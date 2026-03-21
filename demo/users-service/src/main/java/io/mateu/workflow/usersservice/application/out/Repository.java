package io.mateu.workflow.usersservice.application.out;

import io.mateu.uidl.interfaces.Identifiable;

import java.util.List;
import java.util.Optional;

public interface Repository<T, IdType> {
    Optional<T> findById(IdType id);

    IdType save(T entity);

    List<T> findAll();

    void deleteAllById(List<IdType> selectedIds);
}
