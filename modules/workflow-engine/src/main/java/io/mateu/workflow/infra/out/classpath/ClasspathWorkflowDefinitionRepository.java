package io.mateu.workflow.infra.out.classpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.DefinitionFileFormat;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Reads workflow definitions from classpath:/workflows/ — {@code *.json}, {@code *.yaml}/{@code *.yml}
 * (including {@code *.workflow.yaml} and {@code *.ec.yaml}) and {@code *.ec}; see
 * {@link DefinitionFileFormat#CLASSPATH_PATTERNS}. Each file must contain a serialized
 * {@link WorkflowDefinition}.
 * Active when workflow.persistence=memory.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
@Slf4j
public class ClasspathWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();

    // Injected so the task repository (and its classpath contracts) is constructed first: a step's
    // `task: <id>` is pinned to <id>@<latest> as the definition loads, exactly as it is on import.
    private final io.mateu.workflow.application.services.TaskReferenceResolver taskReferenceResolver;

    public ClasspathWorkflowDefinitionRepository(
            io.mateu.workflow.application.services.TaskReferenceResolver taskReferenceResolver) {
        this.taskReferenceResolver = taskReferenceResolver;
        loadFromClasspath();
    }

    private static final ObjectMapper YAML_MAPPER = new YAMLMapper();

    private void loadFromClasspath() {
        var resolver = new PathMatchingResourcePatternResolver();
        try {
            // The same files the JPA importer reads: .json, .yaml/.yml (order.workflow.yaml and
            // order.ec.yaml among them) and .ec, whose content is sniffed for JSON or YAML.
            var resources = new java.util.ArrayList<Resource>();
            for (var pattern : DefinitionFileFormat.CLASSPATH_PATTERNS) {
                resources.addAll(Arrays.asList(resolver.getResources("classpath:/workflows/" + pattern)));
            }
            // cron.yaml sits next to the definitions but holds schedules (ClasspathCronScheduleLoader).
            resources.removeIf(r -> io.mateu.workflow.analysis.ScheduleRules.isCronFileName(r.getFilename()));
            // events.yaml too: event triggers naming their workflow (ClasspathEventTriggerLoader).
            resources.removeIf(r -> io.mateu.workflow.analysis.EventTriggerRules.isEventFileName(r.getFilename()));
            for (var resource : resources) {
                try {
                    String filename = resource.getFilename();
                    byte[] bytes = resource.getInputStream().readAllBytes();
                    WorkflowDefinition def = DefinitionFileFormat.isYaml(filename, bytes)
                            ? YAML_MAPPER.readValue(bytes, WorkflowDefinition.class)
                            : pojoFromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), WorkflowDefinition.class);
                    if (def.steps() != null) {
                        final String defId = def.id();
                        List<Step> stepsWithId = def.steps().stream()
                                .map(s -> s.withWorkflowDefinitionId(defId))
                                .toList();
                        def = new WorkflowDefinition(
                                def.id(), def.name(), def.version(), def.description(),
                                def.limitConcurrentExecutions(),
                                def.maxConcurrentExecutions(), def.enqueueOnLimit(),
                                def.cronExpression(), def.defaultMaxStepExecutions(), stepsWithId)
                                .withMaxSteps(def.maxSteps())
                                .withLayout(def.layout())
                                .withProcessLock(def.processLock())
                                .withSyncInvocation(def.syncInvocation())
                                .withSchedules(def.schedules())
                                .withTriggers(def.triggers())
                                .withTags(def.tags())
                                .withRetention(def.retention())
                                // The narrower constructor above declares ACTIVE: keep what the file says.
                                .withDeclaredStatus(def.declaredStatus());
                    }
                    def = taskReferenceResolver.resolve(def);
                    definitions.put(def.id(), def);
                    log.info("Loaded workflow definition '{}' from classpath:{}", def.id(), resource.getFilename());
                } catch (Exception e) {
                    log.error("Failed to load workflow definition from classpath:{}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.warn("No workflow definitions found at classpath:/workflows/ — directory may not exist");
        }
    }

    @Override
    public Optional<WorkflowDefinition> findById(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    @Override
    public String save(WorkflowDefinition def) {
        definitions.put(def.id(), def);
        return def.id();
    }

    @Override
    public List<WorkflowDefinition> findAll() {
        return List.copyOf(definitions.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(definitions::remove);
    }
}
