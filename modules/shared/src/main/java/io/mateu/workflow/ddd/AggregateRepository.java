package io.mateu.workflow.ddd;

import java.util.List;
import java.util.Optional;

public interface AggregateRepository<AggregateType, IdType> {

   Optional<AggregateType> findById(IdType id);

    AggregateType save(AggregateType aggregateRoot);

    AggregateType delete(AggregateType aggregateRoot);

    List<AggregateType> findAll();


}
