package io.mateu.workflow.infra.out.persistence.shared;

import io.mateu.core.infra.declarative.Entity;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.Repository;
import io.mateu.workflow.infra.out.shared.Operation;
import io.mateu.workflow.infra.out.shared.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;
import static io.mateu.core.infra.reflection.read.FieldByNameProvider.getFieldByName;
import static io.mateu.core.infra.reflection.read.ValueProvider.getValue;

@RequiredArgsConstructor
@Slf4j
public abstract class DBRepository<EntityType extends Entity<IdType>, IdType> implements Repository<EntityType, IdType> {

    private final GenericEntityRepository repository;
    private final StreamBridge streamBridge;

    @Override
    @Transactional
    public void saveAll(List<EntityType> entities) {
        entities.forEach(entity -> repository.save(new GenericEntity("" + entity.id(), name(entity), entityClass().getSimpleName(), 0, toJson(entity))));
        entities.forEach(entity -> {
            // 2. Lanzar evento a Redpanda
            // El nombre 'genericEntity-out-0' es una convención, puedes llamarlo como quieras
            boolean enviado = streamBridge.send("genericEntity-out-0", new OutboxEvent(
                    entity.getClass().getSimpleName(),
                    "" + entity.id(),
                    Operation.Modify,
                    toJson(entity)
            ));

            if (enviado) {
                log.info("Evento enviado a Redpanda correctamente");
            }
        });
    }

    public Optional<EntityType> findById(IdType id) {
        return repository.findByIdAndType("" + id, entityClass().getSimpleName()).stream().findFirst().map(entity -> mapFromJson(entity.getJson()));
    }

    @Override
    public List<EntityType> findAll() {
        return repository.findByType(entityClass().getSimpleName()).stream().map(entity -> mapFromJson(entity.getJson())).toList();
    }

    private EntityType mapFromJson(String json) {
        return (EntityType) pojoFromJson(json, entityClass());
    }

    public abstract Class<?> entityClass();

    @Override
    public ListingData<EntityType> search(String searchText, Pageable pageable) {
        if (searchText == null) {
            searchText = "";
        }
        var found = repository.findAllByTypeAndNameContainingIgnoreCase(entityClass().getSimpleName(), searchText).stream()
                .map(o -> mapFromJson(o.getJson()))
                .toList();
        return new ListingData<>(new Page<>(
                searchText,
                pageable.size(),
                pageable.page(),
                found.size(),
                found.stream()
                        .skip((long) pageable.page() * pageable.size())
                        .limit(pageable.size())
                        .toList()));
    }

    @Override
    public void deleteAllById(List<IdType> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(id -> "" + id).toList());
        selectedIds.forEach(id -> {
            boolean enviado = streamBridge.send("genericEntity-out-0", new OutboxEvent(
                    entityClass().getSimpleName(),
                    "" + id,
                    Operation.Delete,
                    null
            ));

            if (enviado) {
                log.info("Evento enviado a Redpanda correctamente");
            }
        });
    }

    private String name(EntityType value) {
        var nameField = getFieldByName(value.getClass(), "name");
        if (nameField != null) {
            return (String) getValue(nameField, value);
        }
        return value.toString();
    }

    public void reset() {
        repository.deleteAll();
    }
}
