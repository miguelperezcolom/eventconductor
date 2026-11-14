package io.mateu.workflow.infra.out.classpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.DefinitionFileFormat;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Reads workflow definitions from every {@code workflows/} directory on the classpath (see
 * {@link ClasspathDefinitionFiles}, which also says which copy wins when an id is in two of them) — {@code *.json}, {@code *.yaml}/{@code *.yml}
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
        this(taskReferenceResolver, io.mateu.workflow.crypto.VariableEncryption.DISABLED);
    }

    /** With the engine's encryption, which a definition declaring sensitive variables needs as it loads. */
    public ClasspathWorkflowDefinitionRepository(
            io.mateu.workflow.application.services.TaskReferenceResolver taskReferenceResolver,
            io.mateu.workflow.crypto.VariableEncryption encryption) {
        this(taskReferenceResolver, encryption, null);
    }

    /**
     * With the agent catalogue too, which an AGENT step's reference is checked against as it loads (as the JPA
     * repository checks it on import): a definition naming an agent it does not publish is refused (logged,
     * skipped).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ClasspathWorkflowDefinitionRepository(
            io.mateu.workflow.application.services.TaskReferenceResolver taskReferenceResolver,
            io.mateu.workflow.crypto.VariableEncryption encryption,
            org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.WorkflowAgentCatalogue> agentCatalogue) {
        this.taskReferenceResolver = taskReferenceResolver;
        this.encryption = encryption == null ? io.mateu.workflow.crypto.VariableEncryption.DISABLED : encryption;
        this.agentCatalogue = agentCatalogue;
        loadFromClasspath();
    }

    private final org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.WorkflowAgentCatalogue> agentCatalogue;

    private void requirePublishedAgents(WorkflowDefinition def) {
        io.mateu.workflow.application.services.WorkflowDefinitionValidator.requirePublishedAgents(def,
                agentCatalogue == null ? null : agentCatalogue.getIfAvailable());
    }

    private static final ObjectMapper YAML_MAPPER = new YAMLMapper();

    // Optional and resolved when used: the trigger stores do not depend on the definitions, but a
    // repository that cannot be built without them would make every test harness wire them too.
    private org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.EventTriggerRepository> eventTriggers;
    private org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.TriggerPauseRepository> triggerPauses;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTriggerStores(
            org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.EventTriggerRepository> eventTriggers,
            org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.TriggerPauseRepository> triggerPauses) {
        this.eventTriggers = eventTriggers;
        this.triggerPauses = triggerPauses;
    }

    /** What a definition declaring sensitive variables needs: refused (logged, skipped) without a key. */
    private final io.mateu.workflow.crypto.VariableEncryption encryption;

    /** See {@link io.mateu.workflow.application.services.DefinitionTriggerCleanup}. */
    private io.mateu.workflow.application.services.DefinitionTriggerCleanup triggerCleanup() {
        return new io.mateu.workflow.application.services.DefinitionTriggerCleanup(
                eventTriggers == null ? null : eventTriggers.getIfAvailable(),
                triggerPauses == null ? null : triggerPauses.getIfAvailable());
    }

    private void loadFromClasspath() {
        try {
            // The same files the JPA importer reads, from every classpath root: .json, .yaml/.yml
            // (order.workflow.yaml and order.ec.yaml among them) and .ec, whose content is sniffed for
            // JSON or YAML. The same id in two roots: the first on the classpath wins.
            var firstWins = new ClasspathDefinitionFiles.FirstWins();
            for (var file : ClasspathDefinitionFiles.scan()) {
                try {
                    String filename = file.filename();
                    byte[] bytes = file.bytes();
                    WorkflowDefinition def = DefinitionFileFormat.isYaml(filename, bytes)
                            ? YAML_MAPPER.readValue(bytes, WorkflowDefinition.class)
                            : pojoFromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), WorkflowDefinition.class);
                    if (!firstWins.claim(def.id(), file)) {
                        continue;
                    }
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
                                .withSearchAttributes(def.searchAttributes())
                                .withSensitiveVariables(def.sensitiveVariables())
                                .withUpdates(def.updates())
                                .withStartPolicy(def.startPolicy())
                                .withBusinessKeyPolicy(def.businessKeyPolicy())
                                // The narrower constructor above declares ACTIVE: keep what the file says.
                                .withDeclaredStatus(def.declaredStatus());
                    }
                    def = taskReferenceResolver.resolve(def);
                    io.mateu.workflow.application.services.WorkflowDefinitionValidator.requireKeysFor(def, encryption);
                    requirePublishedAgents(def);
                    definitions.put(def.id(), def);
                    log.info("Loaded workflow definition '{}' from {}", def.id(), file.location());
                } catch (Exception e) {
                    log.error("Failed to load workflow definition from {}", file.location(), e);
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
        io.mateu.workflow.application.services.WorkflowDefinitionValidator.requireKeysFor(def, encryption);
        requirePublishedAgents(def);
        definitions.put(def.id(), def);
        triggerCleanup().definitionSaved(def);
        return def.id();
    }

    @Override
    public List<WorkflowDefinition> findAll() {
        return List.copyOf(definitions.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(definitions::remove);
        triggerCleanup().definitionsDeleted(ids);
    }
}
