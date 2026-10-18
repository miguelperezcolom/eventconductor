package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessSearchAttributeRepository;
import io.mateu.workflow.search.SearchAttributeValue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code process_search_attribute} on the engine's database (jpa persistence). */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
public class JpaProcessSearchAttributeRepository implements ProcessSearchAttributeRepository {

    /** The most ids one IN list carries (Oracle's limit is 1000). */
    private static final int CHUNK = 500;

    private final ProcessSearchAttributeEntityRepository repository;

    public JpaProcessSearchAttributeRepository(ProcessSearchAttributeEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<SearchAttributeValue> findByProcessId(String processId) {
        return repository.findAllByProcessId(processId).stream().map(ProcessSearchAttributeEntity::toValue).toList();
    }

    @Override
    public Map<String, List<SearchAttributeValue>> findByProcessIds(Collection<String> processIds) {
        var found = new LinkedHashMap<String, List<SearchAttributeValue>>();
        var ids = List.copyOf(processIds);
        for (int from = 0; from < ids.size(); from += CHUNK) {
            repository.findAllByProcessIdIn(ids.subList(from, Math.min(ids.size(), from + CHUNK))).forEach(entity ->
                    found.computeIfAbsent(entity.getProcessId(), id -> new ArrayList<>()).add(entity.toValue()));
        }
        return found;
    }

    /**
     * Delete what is there, then insert the new values — in the caller's transaction when there is one
     * (the process save), so the attributes commit or roll back with the process they describe.
     */
    @Override
    @Transactional
    public void replace(String processId, List<SearchAttributeValue> values) {
        var existing = repository.findAllByProcessId(processId);
        if (!existing.isEmpty()) {
            repository.deleteAll(existing);
            repository.flush();
        }
        if (values != null && !values.isEmpty()) {
            repository.saveAll(values.stream().map(value -> new ProcessSearchAttributeEntity(processId, value)).toList());
        }
    }

    @Override
    @Transactional
    public int deleteByProcessIds(Collection<String> processIds) {
        var ids = List.copyOf(processIds);
        var deleted = 0;
        for (int from = 0; from < ids.size(); from += CHUNK) {
            deleted += repository.deleteAllByProcessIdIn(ids.subList(from, Math.min(ids.size(), from + CHUNK)));
        }
        return deleted;
    }
}
