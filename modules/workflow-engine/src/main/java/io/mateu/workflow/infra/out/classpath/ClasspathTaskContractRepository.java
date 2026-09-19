package io.mateu.workflow.infra.out.classpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.tasks.TaskContract;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Memory-mode {@link TaskContractRepository}: loads {@code classpath:/tasks/*.ectask} (plus
 * {@code .json}/{@code .yaml}/{@code .yml}) at startup and keeps them in the heap, versioned by id.
 * The counterpart of {@code ClasspathWorkflowDefinitionRepository} for task contracts, so a service
 * bundling a {@code tasks/} folder gets them without a database — which is what the embedded and
 * test paths run on. The JPA implementation is what a deployed engine uses.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
@Slf4j
public class ClasspathTaskContractRepository implements TaskContractRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new YAMLMapper();

    /** id -> (version -> contract), highest version last. */
    private final Map<String, NavigableMap<Integer, TaskContract>> byId = new ConcurrentHashMap<>();

    public ClasspathTaskContractRepository() {
        load();
    }

    private void load() {
        var resolver = new PathMatchingResourcePatternResolver();
        try {
            var resources = new ArrayList<Resource>();
            resources.addAll(List.of(resolver.getResources("classpath:/tasks/*.ectask")));
            resources.addAll(List.of(resolver.getResources("classpath:/tasks/*.json")));
            resources.addAll(List.of(resolver.getResources("classpath:/tasks/*.{yaml,yml}")));
            for (var resource : resources) {
                try {
                    var filename = resource.getFilename();
                    var mapper = filename != null && filename.endsWith(".json") ? JSON : YAML;
                    var content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    save(mapper.readValue(content, TaskContract.class));
                    log.info("Loaded task contract from classpath:tasks/{}", filename);
                } catch (Exception e) {
                    log.error("Failed to load task contract from classpath:tasks/{}", resource.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.warn("No task contracts found at classpath:/tasks/ — directory may not exist");
        }
    }

    @Override
    public Optional<TaskContract> find(String id, int version) {
        var versions = byId.get(id);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    @Override
    public Optional<TaskContract> findLatest(String id) {
        var versions = byId.get(id);
        return versions == null || versions.isEmpty()
                ? Optional.empty()
                : Optional.of(versions.lastEntry().getValue());
    }

    @Override
    public List<TaskContract> findAll() {
        return byId.values().stream().flatMap(v -> v.values().stream()).toList();
    }

    @Override
    public String save(TaskContract contract) {
        byId.computeIfAbsent(contract.id(), k -> new ConcurrentSkipListMap<>())
                .put(contract.version(), contract);
        return contract.ref();
    }
}
