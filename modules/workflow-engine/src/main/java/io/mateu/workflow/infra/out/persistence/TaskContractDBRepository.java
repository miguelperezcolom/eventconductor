package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.tasks.TaskContract;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * JPA {@link TaskContractRepository} for {@code jpa} mode. Stores each contract whole as JSON with
 * the id, version, group and topic lifted into columns so "the latest version of this id" is an
 * indexed lookup.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class TaskContractDBRepository implements TaskContractRepository {

    private final TaskContractEntityRepository repository;

    @Override
    public Optional<TaskContract> find(String id, int version) {
        return repository.findByContractIdAndVersion(id, version).map(this::toContract);
    }

    @Override
    public Optional<TaskContract> findLatest(String id) {
        return repository.findFirstByContractIdOrderByVersionDesc(id).map(this::toContract);
    }

    @Override
    public List<TaskContract> findAll() {
        return repository.findAll().stream().map(this::toContract).toList();
    }

    @Override
    public String save(TaskContract contract) {
        repository.save(new TaskContractEntity(
                contract.ref(), contract.id(), contract.version(), contract.group(),
                contract.topic(), toJson(contract)));
        return contract.ref();
    }

    private TaskContract toContract(TaskContractEntity entity) {
        return pojoFromJson(entity.getContractJson(), TaskContract.class);
    }
}
